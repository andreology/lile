package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.config.FormProcessingProperties;
import com.contentmanagement.forms.api.model.FormDocument;
import com.contentmanagement.forms.api.model.ProcessingMode;
import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class OpenCvOnlyExtractionStrategy implements FormExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenCvOnlyExtractionStrategy.class);

    private final FormProcessingProperties properties;
    private final OpenCvImageLoader imageLoader;
    private final OpenCvLayoutAnalyzer layoutAnalyzer;
    private final FormDocumentAssembler assembler;
    private final TesseractOcrEngine ocrEngine;

    public OpenCvOnlyExtractionStrategy(FormProcessingProperties properties,
                                        OpenCvImageLoader imageLoader,
                                        OpenCvLayoutAnalyzer layoutAnalyzer,
                                        FormDocumentAssembler assembler,
                                        TesseractOcrEngine ocrEngine) {
        this.properties = properties;
        this.imageLoader = imageLoader;
        this.layoutAnalyzer = layoutAnalyzer;
        this.assembler = assembler;
        this.ocrEngine = ocrEngine;
    }

    @Override
    public ProcessingMode supportedMode() {
        return ProcessingMode.OPENCV_ONLY;
    }

    @Override
    public FormDocument extract(FormExtractionContext context) {
        MultipartFile sourceFile = context.sourceFile();
        if (sourceFile != null) {
            log.info("Received file '{}' but OPENCV_ONLY mode currently operates on configured resources", sourceFile.getOriginalFilename());
        }

        List<String> resources = properties.getFallbackImageResources();
        if (resources.isEmpty()) {
            throw new IllegalStateException("No fallback images configured for OPENCV_ONLY mode");
        }

        List<PageLayout> pages = new ArrayList<>();
        DetectionDiagnostics diagnostics = new DetectionDiagnostics();
        int pageIndex = 0;
        for (String resourcePath : resources) {
            Mat image = imageLoader.loadClasspathImage(resourcePath);
            try {
                PageLayout layout = layoutAnalyzer.analyze(image, pageIndex);
                diagnostics.record(layout);
                pages.add(applyOcr(layout, image));
                pageIndex++;
            } finally {
                image.release();
            }
        }

        FormDocument document = assembler.assemble(pages, properties.getBaseUnit());
        diagnostics.logSummary(log);
        return document;
    }

    private PageLayout applyOcr(PageLayout layout, Mat image) {
        if (!properties.isOcrEnabled()) {
            return layout;
        }

        List<DetectedComponent> enriched = new ArrayList<>(layout.components().size());
        for (DetectedComponent component : layout.components()) {
            String text = ocrEngine.recognize(image, component.boundingBox());
            enriched.add(new DetectedComponent(
                    component.index(),
                    component.type(),
                    component.boundingBox(),
                    text != null ? text : component.text(),
                    component.confidence(),
                    component.widgetType()
            ));
        }
        return new PageLayout(layout.pageIndex(), layout.width(), layout.height(), enriched);
    }
}
