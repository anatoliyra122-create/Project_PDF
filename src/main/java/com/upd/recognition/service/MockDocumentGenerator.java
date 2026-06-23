package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.dto.DocumentItemDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Генератор тестовых данных УПД.
 * Используется как заглушка при отсутствии Tesseract или когда PDF не содержит
 * распознаваемых табличных данных. Детерминирован — один файл → одни данные.
 */
@Component
public class MockDocumentGenerator {

    private static final String[] ITEM_NAMES = {
        "Ноутбук ASUS VivoBook 15 X1504",
        "Принтер HP LaserJet Pro M404n",
        "Монитор Samsung 24\" S24A336NHU",
        "Мышь беспроводная Logitech M185",
        "Клавиатура механическая Keychron K2",
        "SSD Kingston A400 512GB",
        "Кабель HDMI 2.0 Cablexpert 2м",
        "Бумага офисная Ballet A4, 80г, 500л",
        "Картридж HP 85A (CE285A)",
        "Коврик для мыши SteelSeries QcK",
        "ИБП APC Back-UPS 650VA",
        "Веб-камера Logitech HD C270",
        "Патч-корд UTP Cat5e 3м",
        "USB-концентратор Hama 4 порта",
        "Тонер-картридж Canon 052",
        "Флэш-накопитель Kingston 32GB USB3.0",
        "Гарнитура Jabra Evolve 20",
        "Сетевой фильтр Pilot ZА/5 5 розеток",
        "Стол компьютерный IKEA Bekant 160x80",
        "Кресло офисное Chairman 696"
    };

    private static final String[] UNITS   = {"шт.", "уп.", "компл.", "кг", "м", "л"};
    private static final String[] TAX_RATES = {"20%", "20%", "20%", "10%", "Без НДС"};

    private static final String[] DOC_PREFIXES = {"УПД-", "СФ-", "ТН-", "ТОРГ12-"};

    /**
     * Генерирует mock-документ. Результат детерминирован по имени файла,
     * поэтому повторные вызовы для одного файла возвращают одинаковые данные.
     */
    public DocumentDto generate(String fileName) {
        Random rnd = new Random(Math.abs(fileName.hashCode()) + 1L);

        String prefix    = DOC_PREFIXES[rnd.nextInt(DOC_PREFIXES.length)];
        String docNumber = prefix + (rnd.nextInt(900) + 100);

        int day   = rnd.nextInt(28)  + 1;
        int month = rnd.nextInt(12)  + 1;
        int year  = 2023 + rnd.nextInt(2);
        String docDate = String.format("%02d.%02d.%d", day, month, year);

        int itemCount = rnd.nextInt(5) + 1;
        List<DocumentItemDto> items = new ArrayList<>();

        // Для разнообразия — не повторяем позиции в одном документе
        List<String> usedNames = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            String name;
            do { name = ITEM_NAMES[rnd.nextInt(ITEM_NAMES.length)]; }
            while (usedNames.contains(name) && usedNames.size() < ITEM_NAMES.length);
            usedNames.add(name);

            String unit    = UNITS[rnd.nextInt(UNITS.length)];
            String taxRate = TAX_RATES[rnd.nextInt(TAX_RATES.length)];

            BigDecimal qty   = BigDecimal.valueOf(rnd.nextInt(50) + 1);
            // Цена: кратна 50 для реалистичности
            long priceRaw = (rnd.nextInt(200) + 1) * 50L + rnd.nextInt(99);
            BigDecimal price = new BigDecimal(priceRaw).setScale(2, RoundingMode.HALF_UP);

            BigDecimal amountNoTax = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);

            BigDecimal taxCoeff;
            switch (taxRate) {
                case "20%"     -> taxCoeff = new BigDecimal("0.20");
                case "10%"     -> taxCoeff = new BigDecimal("0.10");
                default        -> taxCoeff = BigDecimal.ZERO;
            }
            BigDecimal taxAmount    = amountNoTax.multiply(taxCoeff).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amountWithTax = amountNoTax.add(taxAmount).setScale(2, RoundingMode.HALF_UP);

            items.add(DocumentItemDto.builder()
                .itemName(name)
                .unit(unit)
                .quantity(qty)
                .pricePerUnit(price)
                .amountWithoutTax(amountNoTax)
                .taxRate(taxRate)
                .amountWithTax(amountWithTax)
                .build());
        }

        return DocumentDto.builder()
            .documentNumber(docNumber)
            .documentDate(docDate)
            .sourceFileName(fileName)
            .parseStatus("OK")
            .items(items)
            .build();
    }
}
