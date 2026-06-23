package com.upd.recognition.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.upd.recognition.dto.DocumentDto;
import com.upd.recognition.service.DocumentParserService;
import com.upd.recognition.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Основной контроллер приложения.
 *
 * Эндпоинты:
 *   GET  /              → главная страница (Thymeleaf)
 *   POST /upload        → загрузка файлов во временную папку сессии
 *   POST /recognize     → запуск парсинга; результат сохраняется в сессии
 *   GET  /export/excel  → скачать результаты в .xlsx
 *   GET  /export/json   → скачать результаты в .json
 *   POST /reset         → сбросить состояние сессии
 */
@Controller
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Value("${app.upload.dir}")
    private String baseUploadDir;

    @Autowired private DocumentParserService parserService;
    @Autowired private ExcelExportService    excelService;
    @Autowired private ObjectMapper          objectMapper;

    // ─── Страница ─────────────────────────────────────────────────────────────

    @GetMapping("/")
    public String index() {
        return "index";
    }

    // ─── Загрузка файлов ──────────────────────────────────────────────────────

    @PostMapping("/upload")
    @ResponseBody
    public ResponseEntity<?> upload(
            @RequestParam("files") MultipartFile[] files,
            HttpSession session) {

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body("Файлы не выбраны");
        }

        // Уникальная папка для каждой сессии
        Path sessionDir = Paths.get(baseUploadDir, session.getId());
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            log.error("Не удалось создать папку загрузки", e);
            return ResponseEntity.internalServerError().body("Ошибка сервера: не удалось создать папку загрузки");
        }

        List<String> saved  = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            String originalName = StringUtils.cleanPath(
                Objects.requireNonNull(file.getOriginalFilename(), "filename"));

            // Проверяем расширение
            String lower = originalName.toLowerCase();
            if (!lower.endsWith(".pdf") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                errors.add(originalName + ": неподдерживаемый формат");
                continue;
            }

            Path target = sessionDir.resolve(originalName);
            try {
                file.transferTo(target.toFile());
                saved.add(originalName);
                log.debug("Сохранён файл: {}", target);
            } catch (IOException e) {
                log.error("Ошибка сохранения файла {}", originalName, e);
                errors.add(originalName + ": ошибка сохранения");
            }
        }

        if (saved.isEmpty()) {
            return ResponseEntity.badRequest().body(
                "Ни один файл не загружен. " + String.join("; ", errors));
        }

        session.setAttribute("uploadDir",      sessionDir.toString());
        session.setAttribute("uploadedFiles",  saved);
        session.removeAttribute("recognizedData"); // сбрасываем старые результаты

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files",  saved);
        response.put("count",  saved.size());
        if (!errors.isEmpty()) response.put("warnings", errors);

        return ResponseEntity.ok(response);
    }

    // ─── Распознавание ────────────────────────────────────────────────────────

    @PostMapping("/recognize")
    @ResponseBody
    public ResponseEntity<?> recognize(
            @RequestParam(name = "mode", defaultValue = "auto") String mode,
            HttpSession session) {

        String sessionDirStr = (String) session.getAttribute("uploadDir");
        @SuppressWarnings("unchecked")
        List<String> fileNames = (List<String>) session.getAttribute("uploadedFiles");

        if (sessionDirStr == null || fileNames == null || fileNames.isEmpty()) {
            return ResponseEntity.badRequest().body("Сначала загрузите файлы");
        }

        boolean useClaudeAI = "claude".equalsIgnoreCase(mode);
        Path sessionDir = Paths.get(sessionDirStr);
        List<DocumentDto> results = new ArrayList<>();

        for (String fileName : fileNames) {
            File file = sessionDir.resolve(fileName).toFile();
            if (!file.exists()) {
                results.add(DocumentDto.builder()
                    .sourceFileName(fileName)
                    .parseStatus("ERROR")
                    .errorMessage("Файл не найден на сервере")
                    .items(Collections.emptyList())
                    .build());
                continue;
            }
            results.add(parserService.parse(file, useClaudeAI));
        }

        session.setAttribute("recognizedData", results);
        return ResponseEntity.ok(results);
    }

    // ─── Обновление данных после inline-редактирования ────────────────────────

    @PostMapping("/update")
    @ResponseBody
    public ResponseEntity<Void> update(
            @RequestBody List<DocumentDto> data,
            HttpSession session) {
        session.setAttribute("recognizedData", data);
        log.info("Данные обновлены пользователем: {} документов", data.size());
        return ResponseEntity.ok().build();
    }

    // ─── Экспорт Excel ────────────────────────────────────────────────────────

    @GetMapping("/export/excel")
    public void exportExcel(HttpSession session, HttpServletResponse response) throws IOException {
        List<DocumentDto> data = getRecognizedData(session);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
            "attachment; filename*=UTF-8''upd_export.xlsx");

        try (OutputStream out = response.getOutputStream()) {
            excelService.export(data, out);
        }
    }

    // ─── Экспорт JSON ─────────────────────────────────────────────────────────

    @GetMapping("/export/json")
    public void exportJson(HttpSession session, HttpServletResponse response) throws IOException {
        List<DocumentDto> data = getRecognizedData(session);

        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Disposition",
            "attachment; filename*=UTF-8''upd_export.json");

        ObjectMapper pretty = objectMapper.copy()
            .enable(SerializationFeature.INDENT_OUTPUT);

        try (OutputStream out = response.getOutputStream()) {
            pretty.writeValue(out, data);
        }
    }

    // ─── Сброс ────────────────────────────────────────────────────────────────

    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<Void> reset(HttpSession session) {
        // Удаляем временные файлы
        String dir = (String) session.getAttribute("uploadDir");
        if (dir != null) {
            try {
                deleteDir(Paths.get(dir));
            } catch (IOException e) {
                log.warn("Не удалось удалить временную папку: {}", dir);
            }
        }
        session.removeAttribute("uploadDir");
        session.removeAttribute("uploadedFiles");
        session.removeAttribute("recognizedData");
        return ResponseEntity.ok().build();
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<DocumentDto> getRecognizedData(HttpSession session) {
        Object attr = session.getAttribute("recognizedData");
        return attr instanceof List ? (List<DocumentDto>) attr : Collections.emptyList();
    }

    private void deleteDir(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
}
