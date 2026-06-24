package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
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

    // ── Извлечение метаданных из имени файла ──────────────────────────────────
    // "ТТН УП-14562" или "УПД № АБ-123" в имени файла
    private static final Pattern P_FNAME_NUM = Pattern.compile(
        "(?:УПД|ТТН|ТН|СФ|ТОРГ-?12)[\\s_-]*((?:[А-ЯЁA-Z]{1,4}[-])?[\\d]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // "21.05.2026" или "05,05,2026" — дата в имени файла
    private static final Pattern P_FNAME_DATE = Pattern.compile(
        "(\\d{1,2}[.,/]\\d{1,2}[.,/]\\d{4})");

    // Компания перед ТТН/УПД: "ООО ПКФ ДИПОС ТТН ...", "ТОРГОВЫЙ ДОМ БМЗ_..."
    private static final Pattern P_FNAME_COMPANY = Pattern.compile(
        "^(?:\\d+__?)?(.+?)(?:\\s*[_-]\\s*(?:ТТН|УПД|ТН|СФ|\\d{2}[.,]))",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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

    /** Минимальное кол-во символов из PDFBox — если меньше, PDF считается сканом */
    private static final int MIN_TEXT_LENGTH = 50;

    /**
     * Минимальная доля «осмысленных» символов (кириллица+латиница) в тексте.
     * Если PDFBox вернул текст, но большинство символов — мусор (спецсимволы,
     * ASCII-art из таблиц и т.п.), будем считать документ «скан-подобным»
     * и попробуем OCR.
     */
    private static final double MIN_ALPHA_RATIO = 0.20;

    @Value("${app.tesseract.enabled:false}")
    private boolean tesseractEnabled;

    @Value("${app.tesseract.data-path:}")
    private String tessDataPath;

    @Value("${app.tesseract.language:rus+eng}")
    private String tessLanguage;

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
     * Извлекает текст из PDF.
     *
     * Стратегия:
     *  1. PDFBox с sortByPosition=true — лучше справляется с табличными PDF.
     *  2. Если текста мало (<50 символов) ИЛИ он «мусорный» (мало букв) — пробуем OCR.
     *  3. Если OCR отключён — возвращаем что есть (может попасть на mock).
     */
    public String extractText(File file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // ← ключ для корректного порядка в таблицах
            String text = stripper.getText(document);
            log.debug("PDF '{}': {} символов, {} страниц",
                file.getName(), text.length(), document.getNumberOfPages());

            if (text.trim().length() >= MIN_TEXT_LENGTH && isReadableText(text)) {
                return text;   // нормальный текстовый PDF
            }

            // PDF-скан или мусорный текст — пробуем OCR через Tesseract
            if (tesseractEnabled) {
                log.info("PDF '{}' похож на скан или таблицу ({} симв., читаемость={}) — запускаю OCR",
                    file.getName(), text.trim().length(),
                    String.format("%.0f%%", alphaRatio(text) * 100));
                return extractTextByOcr(document, file.getName());
            }

            log.debug("PDF '{}': Tesseract отключён, возвращаю сырой текст PDFBox", file.getName());
            return text;
        }
    }

    /** Возвращает true, если в тексте достаточно букв (кириллица + латиница). */
    private boolean isReadableText(String text) {
        return alphaRatio(text) >= MIN_ALPHA_RATIO;
    }

    private double alphaRatio(String text) {
        if (text == null || text.isEmpty()) return 0.0;
        long alphaCount = text.chars()
            .filter(c -> Character.isLetter(c))
            .count();
        return (double) alphaCount / text.length();
    }

    /**
     * Рендерит каждую страницу PDF в изображение и запускает Tesseract OCR.
     */
    private String extractTextByOcr(PDDocument document, String fileName) {
        List<File> pageImages = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                // 300 DPI — оптимально для табличных документов (600 DPI избыточно и снижает качество OCR)
                BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.GRAY);
                File tmpImg = Files.createTempFile("page_" + i + "_", ".png").toFile();
                ImageIO.write(img, "PNG", tmpImg);
                pageImages.add(tmpImg);

                String pageText = ocrImage(tmpImg);
                if (pageText != null && !pageText.isBlank()) {
                    combined.append(pageText).append("\n");
                }
                log.debug("OCR страница {}/{} '{}': {} символов", i + 1, document.getNumberOfPages(), fileName, pageText == null ? 0 : pageText.length());
            }
        } catch (Exception e) {
            log.error("Ошибка OCR для '{}': {}", fileName, e.getMessage());
        } finally {
            pageImages.forEach(File::delete);
        }
        String result = combined.toString();
        if (!result.isBlank()) {
            log.debug("OCR полный текст для '{}' (первые 1000 симв.):\n{}", fileName,
                result.substring(0, Math.min(1000, result.length())));
        }
        return result;
    }

    /** Вызывает Tesseract через Tess4J для одного изображения. */
    private String ocrImage(File imageFile) {
        try {
            Class<?> tc = Class.forName("net.sourceforge.tess4j.Tesseract");
            Object t = tc.getDeclaredConstructor().newInstance();
            if (!tessDataPath.isBlank())
                tc.getMethod("setDatapath", String.class).invoke(t, tessDataPath);
            tc.getMethod("setLanguage", String.class).invoke(t, tessLanguage);
            // PSM 6 — единый блок текста: лучше подходит для табличных документов (ТТН, УПД)
            tc.getMethod("setPageSegMode", int.class).invoke(t, 6);
            // OEM 1 — только LSTM (нейросетевой движок): точнее для русского языка
            tc.getMethod("setOcrEngineMode", int.class).invoke(t, 1);
            return (String) tc.getMethod("doOCR", File.class).invoke(t, imageFile);
        } catch (ClassNotFoundException e) {
            log.warn("Tess4J не найден в classpath");
            return "";
        } catch (Throwable e) {
            log.warn("OCR изображения не удался: {}", e.getMessage());
            return "";
        }
    }

    // ─── Основной разбор ──────────────────────────────────────────────────────

    public DocumentDto parseText(String text, String fileName) {
        log.debug("parseText '{}': {} символов, первые 500 симв.:\n{}",
            fileName, text == null ? 0 : text.length(),
            text == null ? "null" : text.substring(0, Math.min(500, text.length())));

        // 1. Извлекаем метаданные документа (из текста)
        String docNumber = findGroup(P_DOC_NUM,      text, 1);
        String docDate   = findDocDate(text);
        String seller    = findGroup(P_SELLER,        text, 1);
        String buyer     = findGroup(P_BUYER,         text, 1);

        // 1a. Если из текста метаданные не найдены — пробуем извлечь из имени файла
        if (docNumber == null) docNumber = findGroup(P_FNAME_NUM,     fileName, 1);
        if (docDate   == null) {
            Matcher dm = P_FNAME_DATE.matcher(fileName);
            if (dm.find()) docDate = dm.group(1).replace(",", ".");
        }
        if (seller == null) {
            // Убираем расширение, затем извлекаем компанию из начала имени файла
            String baseName = fileName.replaceAll("(?i)\\.pdf$|\\.jpg$|\\.jpeg$", "");
            Matcher cm = P_FNAME_COMPANY.matcher(baseName);
            if (cm.find()) seller = cm.group(1).replaceAll("[_]", " ").trim();
        }

        // 2. Пробуем key-value (ключевые слова) — подходит для ваших УПД/ТТН
        List<DocumentItemDto> items = parseKeyValue(text);

        // 3. Если не сработало — пробуем табличный формат
        if (items.isEmpty()) {
            items = parseTableRows(text);
        }

        // 4. Если и это не дало результата — заглушка с метаданными из имени файла
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
