package com.upd.recognition.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Документ УПД / счёт-фактура / накладная с набором позиций товаров.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDto {

    /** Номер документа (например, "УПД-123", "СФ-45/А") */
    private String documentNumber;

    /** Дата документа в формате ДД.ММ.ГГГГ */
    private String documentDate;

    /** Имя исходного файла */
    private String sourceFileName;

    /** Статус разбора: "OK" или "ERROR" */
    private String parseStatus;

    /** Сообщение об ошибке (заполняется при parseStatus = "ERROR") */
    private String errorMessage;

    /** Продавец */
    private String seller;

    /** Покупатель */
    private String buyer;

    /** Список позиций товаров/услуг */
    private List<DocumentItemDto> items;
}
