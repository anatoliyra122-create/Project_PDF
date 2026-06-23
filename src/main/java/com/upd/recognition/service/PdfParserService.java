package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.*;

/**
 * Парсер PDF через Apache PDFBox 3.x.
 *
 * Стратегия (для каждого документа):
 *  1. Ключевые-значения: шаблоны под конкретный формат УПД/ТТН (приоритет).
 *  2. Табличные строки: эвристика по пробелам (запасной вариант).
 *  3. Mock-данные: если оба варианта не дали позиций.
 */
@Service
public class PdfParserService {

    private static final Logger log = LoggerFactory.getLogger(PdfParserService.class);

    // ── Номер документа ───────────────────────────────────────────────────────
    // "Счет-фактура№ УП-14562", "УПД № 123", "ТТН №25", "СФ-45/А"
    private static final Pattern P_DOC_NUM = Pattern.compile(
        "(?:Счет-фактура|счёт-фактура|УПД|ТОРГ-?12|ТТН|Накладная)[№\\s#]*([\\-\\d/А-Яа-яA-Za-z]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Дата ─────────────────────────────────────────────────────────────────
    // "от 21 мая 2026" или "от 21.05.2026" или "от 12/03/2024"
    private static final Pattern P_DOC_DATE_RU = Pattern.compile(
        "от\\s+(\\d{1,2}\\s+[а-яё]+\\s+\\d{4})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern P_DOC_DATE_NUM = Pattern.compile(
        "от\\s*(\\d{1,2}[./]\\d{1,2}[./]\\d{2,4})");

    // ── Продавец / Покупатель ─────────────────────────────────────────────────
    private static final Pattern P_SELLER = Pattern.compile(
        "Продавец[:\\s]+(.+?)(?=\\s*;|\\s*\\n|\\s*Адрес|$)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern P_BUYER = Pattern.compile(
        "Покупатель[:\\s]+(.+?)(?=\\s*;|\\s*\\n|\\s*Адрес|$)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Наименование товара ───────────────────────────────────────────────────
    private static final Pattern P_PRODUCT = Pattern.compile(
        "Наименование товара[^:]*:\\s*(.+?)(?=\\s*;|\\s*Номер столбца|\\s*\\n|$)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Единица измерения ─────────────────────────────────────────────────────
    private static final Pattern P_UNIT = Pattern.compile(
        "Единица измерения[^:]*:\\s*(\\S+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Количество ────────────────────────────────────────────────────────────
    private static final Pattern P_QTY = Pattern.compile(
        "Количество[^:]*:\\s*([\\d\\s]+[,.]\\d+|\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Цена ─────────────────────────────────────────────────────────────────
    private static final Pattern P_PRICE = Pattern.compile(
        "Цена[^:]*:\\s*([\\d\\s]+[,.]\\d+|\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Сумма без НДС ─────────────────────────────────────────────────────────
    private static final Pattern P_AMOUNT = Pattern.compile(
        "Стоимость товаров[^:]*без налога[^:]*:\\s*([\\d\\s]+[,.]\\d+|\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Ставка НДС ────────────────────────────────────────────────────────────
    private static final Pattern P_TAX_RATE = Pattern.compile(
        "Налоговая ставка[^:]*:\\s*(\\d+%|Без НДС)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Сумма с НДС ───────────────────────────────────────────────────────────
    private static final Pattern P_TOTAL = Pattern.compile(
        "Стоимость товаров[^:]*с налогом[^:]*:\\s*([\\d\\s]+[,.]\\d+|\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Табличная строка (запасной вариант) ────────────────────────────────────
    private static final Pattern P_TABLE_ROW = Pattern.compile(
        "([А-ЯЁа-яёA-Za-z][^\\n]{4,60}?)\\s{2,}" +
        "(шт\\.|кг|л\\b|т\\b|м\\b|уп\\.|компл\\.|пар[ао]|п\\.м\\.?)\\s+" +
        "([\\d\\s]+[.,]\\d{0,2})\\s+([\\d\\s]+[.,]\\d{2})");

    // ── Диаметр ───────────────────────────────────────────────────────────────
    private static final Pattern P_DIAM = Pattern.compile(
        "ф(\\d{2,3})(?:\\s*мм)?|(?:d|Ø)(\\d{2,3})",
        Pattern.CASE_INSENSITIVE);

    // ── Марка стали ───────────────────────────────────────────────────────────
    private static final Pattern P_STEEL = Pattern.compile(
        "([А-ЯЁ]\\d{3}[А-ЯЁ]?)",
        Pattern.UNICODE_CASE);

    // ── ГОСТ ──────────────────────────────────────────────────────────────────
    private static final Pattern P_GOST = Pattern.compile(
        "ГОСТ\\s?Р?\\s?[\\-\\d]+(?:[\\-.]\\d+)*",
        Pattern.UNICODE_CASE);

    private static final Map<String, String> MONTHS = new HashMap<>();
    static {
        MONTHS.put("января","01"); MONTHS.put("февраля","02"); MONTHS.put("марта","03");
        MONTHS.put("апреля","04"); MONTHS.put("мая","05");     MONTHS.put("июня","06");
        MONTHS.put("июля","07");   MONTHS.put("августа","08"); MONTHS.put("сентября","09");
        MONTHS.put("октября","10"); MONTHS.put("ноября","11"); MONTHS.put("декабря","12");
    }

    @Autowired
    private MockDocumentGenerator mockGenerator;

    // ─── Публичный метод ──────────────────────────────────────────────────────

    public DocumentDto parse(File file) {
        try {
            String text = extractText(file);
            return parseText(text, file.getName());
        } catch (Exception e) {
            log.error("Ошибка чтения PDF '{}': {}", file.getName(), e.getMessage());
            return DocumentDto.builder()
                .sourceFileName(file.getName())
                .parseStatus("ERROR")
                .errorMessage("Ошибка чтения PDF: " + e.getMessage())
                .items(Collections.emptyList())
                .build();
        }
    }

    /**
     * Извлекает сырой текст из PDF-файла (для передачи в Claude или другой парсер).
     */
    public String extractText(File file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("PDF '{}': {} символов, {} страниц",
                file.getName(), text.length(), document.getNumberOfPages());
            return text;
        }
    }

    // ─── Основной разбор ──────────────────────────────────────────────────────

    public DocumentDto parseText(String text, String fileName) {

        // 1. Извлекаем метаданные документа
        String docNumber = findGroup(P_DOC_NUM,      text, 1);
        String docDate   = findDocDate(text);
        String seller    = findGroup(P_SELLER,        text, 1);
        String buyer     = findGroup(P_BUYER,         text, 1);

        // 2. Пробуем key-value (ключевые слова) — подходит для ваших УПД/ТТН
        List<DocumentItemDto> items = parseKeyValue(text);

        // 3. Если не сработало — пробуем табличный формат
        if (items.isEmpty()) {
            items = parseTableRows(text);
        }

        // 4. Если и это не дало результата — mock с найденными метаданными
        if (items.isEmpty()) {
            log.debug("PDF '{}': паттерны не сработали — использую mock-данные", fileName);
            DocumentDto mock = mockGenerator.generate(fileName);
            if (docNumber != null) mock.setDocumentNumber(docNumber);
            if (docDate   != null) mock.setDocumentDate(docDate);
            if (seller    != null) mock.setSeller(clean(seller));
            if (buyer     != null) mock.setBuyer(clean(buyer));
            return mock;
        }

        return DocumentDto.builder()
            .documentNumber(docNumber != null ? docNumber : "—")
            .documentDate(docDate     != null ? docDate   : "—")
            .seller(seller != null ? clean(seller) : null)
            .buyer(buyer   != null ? clean(buyer)  : null)
            .sourceFileName(fileName)
            .parseStatus("OK")
            .items(items)
            .build();
    }

    // ─── Разбор по ключевым словам ────────────────────────────────────────────

    private List<DocumentItemDto> parseKeyValue(String text) {
        String productName = findGroup(P_PRODUCT,  text, 1);
        if (productName == null) return Collections.emptyList();

        productName = productName.replaceAll(";$", "").trim();

        String unit    = findGroup(P_UNIT,  text, 1);
        BigDecimal qty   = parseBd(findGroup(P_QTY,    text, 1));
        BigDecimal price = parseBd(findGroup(P_PRICE,  text, 1));
        BigDecimal noTax = parseBd(findGroup(P_AMOUNT, text, 1));
        String taxRate   = findGroup(P_TAX_RATE, text, 1);
        BigDecimal total = parseBd(findGroup(P_TOTAL, text, 1));

        // Вычисляем пропущенные суммы
        if (noTax == null && qty != null && price != null) {
            noTax = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        }
        if (total == null && noTax != null && taxRate != null) {
            try {
                BigDecimal rate = new BigDecimal(taxRate.replace("%",""))
                    .divide(new BigDecimal("100"));
                total = noTax.multiply(BigDecimal.ONE.add(rate))
                    .setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ignored) {}
        }

        DocumentItemDto item = DocumentItemDto.builder()
            .itemName(productName)
            .unit(unit   != null ? unit    : "—")
            .quantity(qty   != null ? qty   : BigDecimal.ZERO)
            .pricePerUnit(price != null ? price : BigDecimal.ZERO)
            .amountWithoutTax(noTax != null ? noTax : BigDecimal.ZERO)
            .taxRate(taxRate != null ? taxRate : "0%")
            .amountWithTax(total != null ? total : BigDecimal.ZERO)
            .build();

        enrichWithMaterialData(productName, item);
        return List.of(item);
    }

    // ─── Разбор табличных строк ───────────────────────────────────────────────

    private List<DocumentItemDto> parseTableRows(String text) {
        List<DocumentItemDto> items = new ArrayList<>();
        Matcher m = P_TABLE_ROW.matcher(text);
        while (m.find()) {
            try {
                BigDecimal qty   = parseBdRaw(m.group(3));
                BigDecimal price = parseBdRaw(m.group(4));
                if (qty == null || price == null) continue;

                BigDecimal noTax     = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
                BigDecimal taxAmount = noTax.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal total     = noTax.add(taxAmount).setScale(2, RoundingMode.HALF_UP);

                String name = m.group(1).trim();
                DocumentItemDto item = DocumentItemDto.builder()
                    .itemName(name)
                    .unit(m.group(2).trim())
                    .quantity(qty)
                    .pricePerUnit(price)
                    .amountWithoutTax(noTax)
                    .taxRate("20%")
                    .amountWithTax(total)
                    .build();
                enrichWithMaterialData(name, item);
                items.add(item);
            } catch (Exception ex) {
                log.debug("Пропуск строки таблицы: {}", ex.getMessage());
            }
        }
        return items;
    }

    // ─── Обогащение данными о материале ──────────────────────────────────────

    private void enrichWithMaterialData(String productName, DocumentItemDto item) {
        // Диаметр: ф32, d32, Ø32
        Matcher dm = P_DIAM.matcher(productName);
        if (dm.find()) {
            String val = dm.group(1) != null ? dm.group(1) : dm.group(2);
            item.setDiameter(val + " мм");
        }

        // Марка стали: А500С, А400 и т.п.
        Matcher sm = P_STEEL.matcher(productName);
        if (sm.find()) item.setSteelGrade(sm.group(1));

        // ГОСТ: ГОСТ 34028-2016, ГОСТ Р 52544-2006
        Matcher gm = P_GOST.matcher(productName);
        if (gm.find()) item.setGost(gm.group(0).trim());
    }

    // ─── Дата документа ──────────────────────────────────────────────────────

    private String findDocDate(String text) {
        // Сначала "21 мая 2026"
        Matcher m = P_DOC_DATE_RU.matcher(text);
        if (m.find()) return convertRussianDate(m.group(1).trim());

        // Потом "21.05.2026"
        m = P_DOC_DATE_NUM.matcher(text);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    private String convertRussianDate(String s) {
        // "21 мая 2026" → "21.05.2026"
        String[] parts = s.trim().split("\\s+");
        if (parts.length != 3) return s;
        String day   = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
        String month = MONTHS.getOrDefault(parts[1].toLowerCase(), "??");
        return day + "." + month + "." + parts[2];
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    private String findGroup(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    /** "19,796" или "19.796" или "803 198,36" → BigDecimal */
    private BigDecimal parseBd(String raw) {
        if (raw == null) return null;
        return parseBdRaw(raw);
    }

    private BigDecimal parseBdRaw(String raw) {
        if (raw == null) return null;
        try {
            String clean = raw.replaceAll("\\s", "").replace(",", ".");
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clean(String s) {
        return s == null ? null : s.replaceAll(";$", "").trim();
    }
}
