package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Сервис экспорта данных в Excel (.xlsx) через Apache POI.
 * Создаёт форматированную таблицу с объединёнными ячейками для шапки документа.
 */
@Service
public class ExcelExportService {

    private static final String[] HEADERS = {
        "Файл", "№ документа", "Дата документа", "Продавец", "Покупатель",
        "Наименование товара", "Диаметр", "Марка стали", "ГОСТ",
        "Ед. изм.", "Количество",
        "Цена (руб.)", "Стоимость без НДС (руб.)", "Ставка НДС", "Стоимость с НДС (руб.)"
    };

    public void export(List<DocumentDto> documents, OutputStream out) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("УПД документы");
            sheet.setDefaultColumnWidth(18);

            // Стили
            CellStyle headerStyle    = buildHeaderStyle(wb);
            CellStyle numberStyle    = buildNumberStyle(wb);
            CellStyle centerStyle    = buildCenterStyle(wb);
            CellStyle errorStyle     = buildErrorStyle(wb);
            CellStyle boldStyle      = buildBoldStyle(wb);
            CellStyle totalStyle     = buildTotalStyle(wb);

            // Строка заголовков
            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(22);
            for (int c = 0; c < HEADERS.length; c++) {
                cell(headerRow, c, HEADERS[c], headerStyle);
            }

            int rowIdx = 1;

            for (DocumentDto doc : documents) {

                if ("ERROR".equals(doc.getParseStatus())) {
                    Row row = sheet.createRow(rowIdx++);
                    cell(row, 0, doc.getSourceFileName(), errorStyle);
                    Cell errCell = row.createCell(1);
                    errCell.setCellValue("ОШИБКА: " + doc.getErrorMessage());
                    errCell.setCellStyle(errorStyle);
                    sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 1, 9));
                    continue;
                }

                List<DocumentItemDto> items = doc.getItems();
                if (items == null || items.isEmpty()) continue;

                int startRow = rowIdx;

                for (int i = 0; i < items.size(); i++) {
                    DocumentItemDto item = items.get(i);
                    Row row = sheet.createRow(rowIdx++);

                    // Первые 5 колонок — общие для документа (потом объединяем)
                    cell(row, 0, i == 0 ? doc.getSourceFileName()  : "", boldStyle);
                    cell(row, 1, i == 0 ? doc.getDocumentNumber()  : "", boldStyle);
                    cell(row, 2, i == 0 ? doc.getDocumentDate()    : "", centerStyle);
                    cell(row, 3, i == 0 ? nvl(doc.getSeller())     : "", null);
                    cell(row, 4, i == 0 ? nvl(doc.getBuyer())      : "", null);

                    // Позиция товара
                    cell(row, 5, item.getItemName(), null);
                    cell(row, 6, nvl(item.getDiameter()),   centerStyle);
                    cell(row, 7, nvl(item.getSteelGrade()), centerStyle);
                    cell(row, 8, nvl(item.getGost()),       null);
                    cell(row, 9, item.getUnit(),            centerStyle);
                    cellNum(row, 10, item.getQuantity(),        numberStyle);
                    cellNum(row, 11, item.getPricePerUnit(),    numberStyle);
                    cellNum(row, 12, item.getAmountWithoutTax(), numberStyle);
                    cell(row, 13, item.getTaxRate(), centerStyle);
                    cellNum(row, 14, item.getAmountWithTax(),   numberStyle);
                }

                // Объединяем ячейки "Файл", "Номер" и "Дата" по всем строкам документа
                if (items.size() > 1) {
                    int endRow = rowIdx - 1;
                    for (int c = 0; c <= 4; c++) mergeSafe(sheet, startRow, endRow, c);
                }
            }

            // Итоговая строка
            appendTotalRow(sheet, rowIdx, documents, numberStyle, totalStyle);

            // Подгоняем ширину колонок
            for (int c = 0; c < HEADERS.length; c++) {
                sheet.autoSizeColumn(c);
                // Минимальная ширина 10 символов
                if (sheet.getColumnWidth(c) < 2560) sheet.setColumnWidth(c, 2560);
            }

            // Заморозим заголовок
            sheet.createFreezePane(0, 1);

            wb.write(out);
        }
    }

    // ─── строка итогов ────────────────────────────────────────────────────────

    private void appendTotalRow(Sheet sheet, int rowIdx, List<DocumentDto> docs,
                                 CellStyle numStyle, CellStyle totalStyle) {
        BigDecimal totalNoTax  = BigDecimal.ZERO;
        BigDecimal totalWithTax = BigDecimal.ZERO;
        int totalItems = 0;

        for (DocumentDto doc : docs) {
            if (doc.getItems() == null) continue;
            for (DocumentItemDto it : doc.getItems()) {
                if (it.getAmountWithoutTax() != null) totalNoTax   = totalNoTax.add(it.getAmountWithoutTax());
                if (it.getAmountWithTax()    != null) totalWithTax = totalWithTax.add(it.getAmountWithTax());
                totalItems++;
            }
        }

        Row row = sheet.createRow(rowIdx);
        cell(row, 0, "ИТОГО (" + totalItems + " позиций)", totalStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 11));
        cellNum(row, 12, totalNoTax,   totalStyle);
        cell(row, 13, "",              totalStyle);
        cellNum(row, 14, totalWithTax, totalStyle);
    }

    // ─── вспомогательные методы ───────────────────────────────────────────────

    private String nvl(String s) { return s != null ? s : "—"; }

    private void mergeSafe(Sheet sheet, int r1, int r2, int col) {
        if (r1 < r2) sheet.addMergedRegion(new CellRangeAddress(r1, r2, col, col));
    }

    private Cell cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
        return c;
    }

    private void cellNum(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value.doubleValue() : 0.0);
        if (style != null) c.setCellStyle(style);
    }

    // ─── фабрики стилей ───────────────────────────────────────────────────────

    private CellStyle buildHeaderStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 11);

        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        setBorder(s, BorderStyle.THIN);
        return s;
    }

    private CellStyle buildNumberStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00"));
        s.setAlignment(HorizontalAlignment.RIGHT);
        setBorder(s, BorderStyle.THIN);
        return s;
    }

    private CellStyle buildCenterStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(s, BorderStyle.THIN);
        return s;
    }

    private CellStyle buildBoldStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        s.setWrapText(true);
        setBorder(s, BorderStyle.THIN);
        return s;
    }

    private CellStyle buildErrorStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setColor(IndexedColors.RED.getIndex());
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(s, BorderStyle.THIN);
        return s;
    }

    private CellStyle buildTotalStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00"));
        s.setAlignment(HorizontalAlignment.RIGHT);
        setBorder(s, BorderStyle.MEDIUM);
        return s;
    }

    private void setBorder(CellStyle s, BorderStyle bs) {
        s.setBorderTop(bs);
        s.setBorderBottom(bs);
        s.setBorderLeft(bs);
        s.setBorderRight(bs);
    }
}
