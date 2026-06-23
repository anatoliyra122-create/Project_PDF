package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collections;

/**
 * Оркестратор: выбирает нужный парсер в зависимости от типа файла и режима.
 *
 * Режимы:
 *   useClaudeAI=false (по умолчанию) — regex-парсинг (PdfParserService / ImageParserService)
 *   useClaudeAI=true                 — сначала Claude AI, при неудаче — regex-парсинг
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    @Autowired private PdfParserService    pdfParser;
    @Autowired private ImageParserService  imageParser;
    @Autowired private ClaudeParserService claudeParser;

    public DocumentDto parse(File file) {
        return parse(file, false);
    }

    public DocumentDto parse(File file, boolean useClaudeAI) {
        String name = file.getName().toLowerCase();
        log.info("Обработка файла: {} (Claude AI: {})", file.getName(), useClaudeAI);

        if (useClaudeAI && claudeParser.isEnabled()) {
            DocumentDto result = tryClaudeParse(file, name);
            if (result != null) return result;
            log.warn("Claude AI не дал результата, переходим на regex: '{}'", file.getName());
        }

        if (name.endsWith(".pdf")) {
            return pdfParser.parse(file);
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return imageParser.parse(file);
        } else {
            log.warn("Неподдерживаемый формат: {}", file.getName());
            return DocumentDto.builder()
                .sourceFileName(file.getName())
                .parseStatus("ERROR")
                .errorMessage("Неподдерживаемый формат файла. Допустимы: PDF, JPEG.")
                .items(Collections.emptyList())
                .build();
        }
    }

    /**
     * Извлекает текст из файла и передаёт его в Claude AI для разбора.
     * Возвращает null если не удалось.
     */
    private DocumentDto tryClaudeParse(File file, String nameLower) {
        try {
            String text;
            if (nameLower.endsWith(".pdf")) {
                text = pdfParser.extractText(file);
            } else {
                text = imageParser.extractText(file);
            }
            if (text == null || text.isBlank()) {
                log.warn("Не удалось извлечь текст для Claude AI из '{}'", file.getName());
                return null;
            }
            return claudeParser.parseText(text, file.getName());
        } catch (Exception e) {
            log.error("Ошибка при подготовке текста для Claude AI: {}", e.getMessage());
            return null;
        }
    }
}
