package com.fnma.rentrollpoc.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fnma.rentrollpoc.service.PdfExtractService;

@RestController
@RequestMapping("/api")
public class PdfExtractController {

    private final PdfExtractService service;

    public PdfExtractController(PdfExtractService service) {
        this.service = service;
    }

    @PostMapping(path = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> extract(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }
        String name = file.getOriginalFilename();
        String contentType = file.getContentType();
        boolean pdfish = (contentType != null && contentType.toLowerCase().contains("pdf"))
                || (StringUtils.hasText(name) && name.toLowerCase().endsWith(".pdf"));
        if (!pdfish) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be a PDF"));
        }
        Map<String, Object> result = service.extractToJsonPayload(file);
        return ResponseEntity.ok(result);
    }
}
