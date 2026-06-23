package com.upd.recognition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Парсер документов через Claude AI (Anthropic API).
 *
 * Включается через:
 *   app.claude.enabled=true
 *   app.claude.api-key=sk-ant-...
 *   app.claude.model=claude-haiku-4-5-20251001  (по умолчанию)
 *
 * Принимает извлечённый текст документа (из PDF или OCR),
 * отправляет в Claude с инструкцией вернуть JSON-структуру документа.
 */
@Service
public class ClaudeParserService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeParserService.class);

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    @Value("${app.claude.enabled:false}")
    private boolean enabled;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    private final RestClient    restClient;
    private final ObjectMapper  objectMapper;

    public ClaudeParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient   = RestClient.create();
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Разбирает текст документа через Claude API.
     *
     * @param text     текст документа (из PDF или OCR)
     * @param fileName имя исходного файла
     * @return DocumentDto или null, если Claude недоступен / не вернул результат
     */
    public DocumentDto parseText(String text, String fileName) {
        if (!isEnabled()) {
            log.debug("Claude AI отключён — пропуск для '{}'", fileName);
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("Пустой текст для Claude AI: '{}'", fileName);
            return null;
        }

        log.info("Отправляем '{}' в Claude AI (модель: {})", fileName, model);

        try {
            Map<String, Object> requestBody = Map.of(
                "model",      model,
                "max_tokens", 4096,
                "messages",   List.of(Map.of(
                    "role",    "user",
                    "content", buildPrompt(text)
                ))
            );

            String responseBody = restClient.post()
                .uri(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

            return parseApiResponse(responseBody, fileName);

        } catch (Exception e) {
            log.error("Ошибка Claude API для '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    // ─── Разбор ответа API ────────────────────────────────────────────────────

    private DocumentDto parseApiResponse(String responseBody, String fileName) {
        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            String   content = root.path("content").get(0).path("text").asText();

            log.debug("Claude ответил {} символов для '{}'", content.length(), fileName);

            String    jsonStr = extractJson(content);
            JsonNode  docJson = objectMapper.readTree(jsonStr);

            List<DocumentItemDto> items = new ArrayList<>();
            JsonNode itemsNode = docJson.path("items");
            if (itemsNode.isArray()) {
                for (JsonNode n : itemsNode) {
                    DocumentItemDto item = DocumentItemDto.builder()
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
                        .build();
                    items.add(item);
                }
            }

            if (items.isEmpty()) {
                log.warn("Claude вернул документ без позиций для '{}'", fileName);
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
            log.error("Ошибка разбора ответа Claude для '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    /** Вырезает JSON-объект из строки (убирает markdown-блоки ``` ```). */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    // ─── Промпт для Claude ────────────────────────────────────────────────────

    private String buildPrompt(String text) {
        // Ограничиваем текст до 8000 символов чтобы не превышать токены
        String truncated = text.length() > 8000 ? text.substring(0, 8000) + "\n[...текст обрезан...]" : text;

        return """
            Ты — система извлечения данных из российских бухгалтерских документов: \
            УПД, счёт-фактура, товарная накладная.
            Из текста ниже извлеки структурированные данные.
            Верни ТОЛЬКО валидный JSON, без объяснений и без markdown-блоков.

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
            - Числовые поля должны быть числами (не строками).
            - Если поле отсутствует — используй null.
            - Если документ содержит несколько позиций — включи все в массив items.

            Текст документа:
            """ + truncated;
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    private String str(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText().trim();
        return v.isEmpty() || v.equals("null") ? null : v;
    }

    private String str(JsonNode node, String field, String def) {
        String v = str(node, field);
        return (v != null) ? v : def;
    }

    private BigDecimal bd(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(n.asText().trim().replace(",", "."));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
