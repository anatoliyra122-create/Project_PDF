# Контекст разработки — upd-recognition

## Что это
Spring Boot приложение для автоматического распознавания российских бухгалтерских документов:
УПД, счёт-фактура, товарная накладная (ТТН). Загружает PDF/JPEG, извлекает структурированные данные
(номер, дата, продавец, покупатель, позиции товаров с ценами), позволяет редактировать и выгружать
результат в Excel/JSON.

## Архитектура

```
DocumentController      — REST API + Thymeleaf UI
DocumentParserService   — оркестратор, выбирает режим
├── PdfParserService    — PDFBox + Tesseract OCR + regex
├── ImageParserService  — Tesseract OCR + regex
├── ClaudeParserService — корпоративный AI Gateway (необязательный режим)
├── ExcelExportService  — экспорт в .xlsx через Apache POI
└── MockDocumentGenerator — заглушка при неудаче парсера
```

## Технический стек
- Java 17, Spring Boot 3.2.0, Thymeleaf
- Apache PDFBox 3.x (извлечение текста из PDF)
- Tess4J 5.10.0 / Tesseract OCR (распознавание сканов)
- Apache POI 5.2.5 (Excel-экспорт)
- Корпоративный AI Gateway `http://BA-SRV-AI-APP01.mr-group.ru:8080/v1` (OpenAI-совместимый)

## Режимы парсинга
1. **Regex (auto)** — PDFBox → если текста мало → Tesseract OCR → regex-паттерны
2. **Claude AI** — извлекаем текст, отправляем в AI Gateway, получаем JSON

## Статус (24.06.2026)

### Сделано
- [x] Создан полный проект (23.06.2026, коммит 0e0500b)
- [x] Интеграция с корпоративным AI Gateway (коммит 9c10274)
- [x] Исправлен NPE в парсинге ответа gateway (коммит 20a0797)
- [x] Собран и запущен JAR (порт 8080)
- [x] Улучшен OCR: PSM 6 + 300 DPI + LSTM (коммит 11ecf54)

### В работе
- [ ] Авторизация AI Gateway — ошибка `401 Invalid bearer token`
  - **Проблема:** токен от `key.mr-group.ru` содержит `kid=A9M0yMVX...`, gateway не знает этот ключ
  - **Что пробовали:** password grant через `key.mr-group.ru` → токен получен (1399 символов), но отклонён
  - **Нужно:** выяснить у администратора gateway правильный Keycloak-endpoint или обновить JWKS-кеш на gateway
  - **Workaround:** пока AI режим не работает → OCR+regex распознаёт только текстовые PDF

### Известные проблемы
- PDF-сканы ТТН (`1__ООО ПКФ ДИПОС...`) — Tesseract читает сетку таблиц как мусор
  → режим regex даёт mock-данные (заглушку); нужен AI Gateway
- AI Gateway: `kid` в JWT не совпадает с JWKS на gateway сервере
  → необходимо уточнить у администратора корректный TOKEN_URL или realm

## Тестовые документы
- `1__ООО ПКФ ДИПОС ТТН УП-14562 - 21.05.2026.pdf` — скан ТТН
- `ТОРГОВЫЙ ДОМ БМЗ_125,00000101_05,05,2026.pdf`

## Запуск
```bash
cd upd-recognition
mvn package -DskipTests
cp target/upd-recognition-0.0.1-SNAPSHOT.jar /tmp/upd-app.jar
java -jar /tmp/upd-app.jar
# http://localhost:8080
```

## Конфигурация AI Gateway
```properties
app.gateway.enabled=true          # включить
app.gateway.base-url=http://BA-SRV-AI-APP01.mr-group.ru:8080
app.gateway.model=openai/gpt-4.1
# ACCESS_TOKEN передаётся через env-переменную (не хранить в файлах)
```
