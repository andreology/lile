package com.contentmanagement.forms.api.controller;

import com.contentmanagement.forms.api.model.PdfProcessingResult;
import com.contentmanagement.forms.api.model.ProcessingMode;
import com.contentmanagement.forms.api.service.PdfProcessingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/pdf", produces = MediaType.APPLICATION_JSON_VALUE)
public class PdfProcessingController {

    private final PdfProcessingService pdfProcessingService;

    public PdfProcessingController(PdfProcessingService pdfProcessingService) {
        this.pdfProcessingService = pdfProcessingService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PdfProcessingResult> uploadPdf(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(name = "mode", required = false) ProcessingMode mode) {
        ProcessingMode effectiveMode = mode != null ? mode : ProcessingMode.OPENCV_ONLY;
        MultipartFile effectiveFile = resolveFileForMode(file, effectiveMode);
        PdfProcessingResult result = pdfProcessingService.processPdf(effectiveFile, effectiveMode);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    private MultipartFile resolveFileForMode(MultipartFile file, ProcessingMode mode) {
        if (mode == ProcessingMode.PDF_BOX_WITH_OPENCV) {
            if (file == null || file.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF source is required for PDF_BOX_WITH_OPENCV mode");
            }
            return file;
        }
        // NOTE: The uploaded file is currently ignored in OPENCV_ONLY mode because the workflow
        // relies on resource-backed imagery until the original PDF asset becomes available.
        return null;
    }
}
