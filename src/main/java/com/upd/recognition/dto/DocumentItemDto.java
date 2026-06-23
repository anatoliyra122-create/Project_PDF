package com.upd.recognition.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Одна позиция товара/услуги в документе УПД/счёт-фактура/накладная.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentItemDto {

    /** Наименование товара (работы, услуги) */
    private String itemName;

    /** Единица измерения (шт., кг, л, м, уп., компл. и т.д.) */
    private String unit;

    /** Количество (объём) */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal quantity;

    /** Цена за единицу без НДС */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal pricePerUnit;

    /** Стоимость товаров без налога */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amountWithoutTax;

    /** Налоговая ставка (например, "20%", "10%", "0%", "Без НДС") */
    private String taxRate;

    /** Стоимость товаров с налогом (итого) */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amountWithTax;

    // ── Специфичные поля для строительных материалов ──────────────────────────

    /** Диаметр (например "32 мм") */
    private String diameter;

    /** Марка стали (например "А500С") */
    private String steelGrade;

    /** ГОСТ (например "ГОСТ 34028-2016") */
    private String gost;
}
