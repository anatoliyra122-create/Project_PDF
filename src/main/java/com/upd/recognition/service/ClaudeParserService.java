package com.upd.recognition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Парсер документов через корпоративный AI Gateway.
 *
 * ВСЕ вызовы LLM идут ТОЛЬКО через:
 *   http://BA-SRV-AI-APP01.mr-group.ru:8080/v1
 *
 * Прямые вызовы api.anthropic.com / api.openai.com — ЗАПРЕЩЕНЫ.
 *
 * Конфигурация (application.properties или env):
 *   app.gateway.enabled=true
 *   app.gateway.base-url=http://BA-SRV-AI-APP01.mr-group.ru:8080
 *   app.gateway.model=openai/gpt-4.1
 *   ACCESS_TOKEN=<keycloak_access_token>
 */
@Service
public class ClaudeParserService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeParserService.class);

    @Value("${app.gateway.enabled:false}")
    private boolean enabled;

    /** Корпоративный gateway. Прямые URL внешних LLM-провайдеров запрещены. */
    @Value("${app.gateway.base-url:http://BA-SRV-AI-APP01.mr-group.ru:8080}")
    private String gatewayBaseUrl;

    @Value("${app.gateway.model:openai/gpt-4.1}")
    private String model;

    /** Токен Keycloak/OIDC из realm AI-Solutions. Не коммитить в репозиторий! */
    @Value("${ACCESS_TOKEN:}")
    private String accessToken;

    private final RestClient   restClient;
    private final ObjectMapper objectMapper;

    public ClaudeParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient   = RestClient.create();
    }

    public boolean isEnabled() {
        return enabled && accessToken != null && !accessToken.isBlank();
    }

    /**
     * Разбирает текст документа через корпоративный AI Gateway.
     *
     * @param text     текст документа (из PDF или OCR)
     * @param fileName имя исходного файла
     * @return DocumentDto или null при ошибке (используй fallback)
     */
    public DocumentDto parseText(String text, String fileName) {
        if (!isEnabled()) {
            log.debug("AI Gateway отключён — пропуск для '{}'", fileName);
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("Пустой текст для AI Gateway: '{}'", fileName);
            return null;
        }

        log.info("Отправляем '{}' в AI Gateway (модель: {})", fileName, model);

        String endpoint = gatewayBaseUrl.stripTrailing() + "/v1/chat/completions";

        try {
            // Инструкции — в role:system (без PII)
            // Текст документа (может содержать PII) — в role:user
            Map<String, Object> requestBody = Map.of(
                "model",    model,
                "stream",   false,   // обязательно false при наличии пользовательских данных
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt()),
                    Map.of("role", "user",   "content", truncate(text, 8000))
                )
            );

            String responseBody = restClient.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    // Читаем тело ошибки для логирования correlation_id
                    throw new GatewayException(resp.getStatusCode().value(), null);
                })
                .body(String.class);

            return parseResponse(responseBody, fileName);

        } catch (GatewayException e) {
            log.error("AI Gateway вернул ошибку {} для '{}': correlation_id={}",
                e.statusCode, fileName, e.correlationId);
            return null;
        } catch (Exception e) {
            log.error("Ошибка вызова AI Gateway для '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    // ─── Разбор ответа (OpenAI-совместимый формат) ───────────────────────────

    private DocumentDto parseResponse(String responseBody, String fileName) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Обработка ошибки в теле ответа (gateway возвращает error.details.correlation_id)
            if (root.has("error")) {
                JsonNode err  = root.path("error");
                String corrId = err.path("details").path("correlation_id").asText(null);
                log.error("AI Gateway ошибка для '{}': code={} correlation_id={}",
                    fileName, err.path("code").asText(), corrId);
                return null;
            }

            // OpenAI-формат: choices[0].message.content
            String content = root.path("choices").get(0)
                .path("message").path("content").asText();

            log.debug("AI Gateway ответил {} символов для '{}'", content.length(), fileName);

            String   jsonStr = extractJson(content);
            JsonNode docJson = objectMapper.readTree(jsonStr);

            List<DocumentItemDto> items = new ArrayList<>();
            for (JsonNode n : docJson.path("items")) {
                items.add(DocumentItemDto.builder()
                    .itemName(str(n, "itemName", "—"))
                    .diameter(str(n, "diameter"))
                    .steelGrade(str(n, "steelGrade"))
                    .gost(str(n, "gost"))
                    .unit(str(n, "unit", "шт."))
                    .quantity(bd(n, "quantity"))
                    .pricePerUnit(bd(n, "pricePerUnit"))
                    .amountWithoutTax(bd(n, "amountWithoutTax"))
                    .taxRate(str(n, "taxRate", "20%"))
                    .amountWithTax(bd(n, "amountWithTax"))
                    .build());
            }

            if (items.isEmpty()) {
                log.warn("AI Gateway вернул документ без позиций для '{}'", fileName);
                return null;
            }

            return DocumentDto.builder()
                .documentNumber(str(docJson, "documentNumber", "—"))
                .documentDate(str(docJson, "documentDate", "—"))
                .seller(str(docJson, "seller"))
                .buyer(str(docJson, "buyer"))
                .sourceFileName(fileName)
                .parseStatus("OK")
                .items(items)
                .build();

        } catch (Exception e) {
            log.error("Ошибка разбора ответа AI Gateway для '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    // ─── Промпт ───────────────────────────────────────────────────────────────

    /** Системные инструкции (без PII — safe для role:system). */
    private String systemPrompt() {
        return """
            Ты — система извлечения данных из российских бухгалтерских документов: \
            УПД, счёт-фактура, товарная накладная.
            Получишь текст документа. Извлеки структурированные данные.
            Верни ТОЛЬКО валидный JSON без объяснений и без markdown-блоков.

            Формат ответа:
            {
              "documentNumber": "номер документа",
              "documentDate": "дата в формате ДД.ММ.ГГГГ",
              "seller": "наименование продавца",
              "buyer": "наименование покупателя",
              "items": [
                {
                  "itemName": "полное наименование товара",
                  "diameter": "диаметр если есть (например '32 мм'), иначе null",
                  "steelGrade": "марка стали если есть (например 'А500С'), иначе null",
                  "gost": "ГОСТ если есть (например 'ГОСТ 34028-2016'), иначе null",
                  "unit": "единица измерения",
                  "quantity": число,
                  "pricePerUnit": число,
                  "amountWithoutTax": число,
                  "taxRate": "ставка НДС (например '20%' или 'Без НДС')",
                  "amountWithTax": число
                }
              ]
            }

            Правила:
            - Числовые поля — числа (не строки).
            - Если поле отсутствует — null.
            - Несколько позиций — все включить в массив items.""";
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    private String truncate(String text, int maxChars) {
        return text.length() > maxChars
            ? text.substring(0, maxChars) + "\n[...текст обрезан...]"
            : text;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    private String str(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText().trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    private String str(JsonNode node, String field, String def) {
        String v = str(node, field);
        return v != null ? v : def;
    }

    private BigDecimal bd(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asText().trim().replace(",", ".")); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ─── Внутренние типы ──────────────────────────────────────────────────────

    private static class GatewayException extends RuntimeException {
        final int    statusCode;
        final String correlationId;
        GatewayException(int code, String corrId) {
            super("Gateway HTTP " + code);
            this.statusCode    = code;
            this.correlationId = corrId;
        }
    }
}
