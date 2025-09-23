package com.contentmanagement.forms.api.service;

import com.contentmanagement.forms.api.config.FormProcessingProperties;
import com.contentmanagement.forms.api.model.FormDocument;
import com.contentmanagement.forms.api.model.PdfProcessingResult;
import com.contentmanagement.forms.api.model.ProcessingMode;
import com.contentmanagement.forms.api.service.extraction.FormExtractionContext;
import com.contentmanagement.forms.api.service.extraction.FormExtractionStrategy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingService.class);

    private final Map<ProcessingMode, FormExtractionStrategy> strategies;
    private final FormProcessingProperties properties;

    public PdfProcessingService(List<FormExtractionStrategy> strategies,
                                FormProcessingProperties properties) {
        this.strategies = new EnumMap<>(ProcessingMode.class);
        for (FormExtractionStrategy strategy : strategies) {
            this.strategies.put(strategy.supportedMode(), strategy);
        }
        this.properties = properties;
    }

    public PdfProcessingResult processPdf(MultipartFile pdfFile, ProcessingMode requestedMode) {
        ProcessingMode effectiveMode = requestedMode != null ? requestedMode : properties.getDefaultMode();
        FormExtractionStrategy strategy = strategies.get(effectiveMode);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for mode " + effectiveMode);
        }

        log.info("Processing request using mode {}", effectiveMode);
        FormDocument document = strategy.extract(new FormExtractionContext(pdfFile, effectiveMode));
        String fileName = resolveFileName(pdfFile, effectiveMode);
        long fileSize = pdfFile != null ? pdfFile.getSize() : 0L;

        return new PdfProcessingResult(fileName, fileSize, effectiveMode, "PROCESSED", document);
    }

    private String resolveFileName(MultipartFile pdfFile, ProcessingMode mode) {
        if (pdfFile != null && !pdfFile.isEmpty()) {
            return pdfFile.getOriginalFilename();
        }
        return String.join(",", properties.getFallbackImageResources());
    }
}
