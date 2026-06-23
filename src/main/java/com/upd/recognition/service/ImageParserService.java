package com.upd.recognition.service;

import com.upd.recognition.dto.DocumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Парсер изображений JPEG через Tesseract OCR (обёртка Tess4J).
 * После OCR текст передаётся в PdfParserService для парсинга по тем же правилам.
 */

@Service
public class ImageParserService {

    private static final Logger log = LoggerFactory.getLogger(ImageParserService.class);

    @Value("${app.tesseract.enabled:false}")
    private boolean tesseractEnabled;

    @Value("${app.tesseract.data-path:}")
    private String tessDataPath;

    @Value("${app.tesseract.language:rus+eng}")
    private String tessLanguage;

    @Autowired
    private MockDocumentGenerator mockGenerator;

    @Autowired
    private PdfParserService pdfParser;

    /**
     * Распознаёт документ из JPEG: извлекает текст через OCR, затем парсит
     * его теми же регулярными выражениями, что и PDF.
     */
    public DocumentDto parse(File file) {
        String text = extractText(file);
        if (text == null || text.isBlank()) {
            log.info("OCR не дал текста для '{}'. Используются mock-данные.", file.getName());
            return mockGenerator.generate(file.getName());
        }
        return pdfParser.parseText(text, file.getName());
    }

    /**
     * Извлекает сырой текст из изображения через Tesseract OCR.
     * Возвращает пустую строку если Tesseract отключён или недоступен.
     */
    public String extractText(File file) {
        if (!tesseractEnabled) {
            log.info("Tesseract отключён (app.tesseract.enabled=false): {}", file.getName());
            return "";
        }
        return doOcr(file);
    }

    private String doOcr(File file) {
        // Динамический импорт — приложение стартует даже без нативной Tesseract DLL
        try {
            Class<?> tc = Class.forName("net.sourceforge.tess4j.Tesseract");
            Object t = tc.getDeclaredConstructor().newInstance();
            if (!tessDataPath.isBlank())
                tc.getMethod("setDatapath", String.class).invoke(t, tessDataPath);
            tc.getMethod("setLanguage", String.class).invoke(t, tessLanguage);

            String text = (String) tc.getMethod("doOCR", File.class).invoke(t, file);
            log.debug("OCR '{}': {} символов", file.getName(), text.length());
            return text;

        } catch (ClassNotFoundException e) {
            log.warn("Класс Tesseract не найден в classpath");
            return "";
        } catch (Throwable e) {
            log.warn("Tesseract недоступен для '{}': {}", file.getName(), e.getMessage());
            return "";
        }
    }
}
