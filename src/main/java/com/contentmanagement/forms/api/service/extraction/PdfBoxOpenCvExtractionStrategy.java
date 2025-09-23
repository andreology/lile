package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.config.FormProcessingProperties;
import com.contentmanagement.forms.api.model.FormDocument;
import com.contentmanagement.forms.api.model.ProcessingMode;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PdfBoxOpenCvExtractionStrategy implements FormExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxOpenCvExtractionStrategy.class);
    private static final float RENDER_DPI = 200f;

    private final OpenCvLayoutAnalyzer layoutAnalyzer;
    private final FormDocumentAssembler assembler;
    private final FormProcessingProperties properties;
    private final TesseractOcrEngine ocrEngine;

    public PdfBoxOpenCvExtractionStrategy(OpenCvLayoutAnalyzer layoutAnalyzer,
                                          FormDocumentAssembler assembler,
                                          FormProcessingProperties properties,
                                          TesseractOcrEngine ocrEngine) {
        this.layoutAnalyzer = layoutAnalyzer;
        this.assembler = assembler;
        this.properties = properties;
        this.ocrEngine = ocrEngine;
    }

    @Override
    public ProcessingMode supportedMode() {
        return ProcessingMode.PDF_BOX_WITH_OPENCV;
    }

    @Override
    public FormDocument extract(FormExtractionContext context) {
        MultipartFile file = context.sourceFile();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("PDF_BOX_WITH_OPENCV mode requires a non-empty PDF file");
        }

        byte[] pdfBytes;
        try {
            pdfBytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read uploaded PDF", ex);
        }

        log.debug("Processing PDF {} ({} bytes) using PDFBox+OpenCV", file.getOriginalFilename(), pdfBytes.length);

        try (RandomAccessRead rar = new RandomAccessReadBuffer(pdfBytes);
             PDDocument document = Loader.loadPDF(rar)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageLayout> pageLayouts = new ArrayList<>();
            DetectionDiagnostics diagnostics = new DetectionDiagnostics();

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                PDPage page = document.getPage(pageIndex);
                BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
                Mat mat = bufferedImageToMat(rendered);
                try {
                    PageLayout layout = layoutAnalyzer.analyze(mat, pageIndex);
                    diagnostics.record(layout);
                    pageLayouts.add(enrichWithText(layout, page, rendered, mat));
                } finally {
                    mat.release();
                }
            }

            FormDocument assembled = assembler.assemble(pageLayouts, properties.getBaseUnit());
            diagnostics.logSummary(log);
            return assembled;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to process PDF file", ex);
        }
    }

    private PageLayout enrichWithText(PageLayout layout, PDPage page, BufferedImage rendered, Mat mat) throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.setSortByPosition(true);

        PDRectangle mediaBox = page.getMediaBox();
        double scaleX = rendered.getWidth() / mediaBox.getWidth();
        double scaleY = rendered.getHeight() / mediaBox.getHeight();

        for (DetectedComponent component : layout.components()) {
            String key = regionKey(layout.pageIndex(), component.index());
            Rectangle2D.Double rect = new Rectangle2D.Double(
                    component.boundingBox().x / scaleX,
                    component.boundingBox().y / scaleY,
                    component.boundingBox().width / scaleX,
                    component.boundingBox().height / scaleY
            );
            stripper.addRegion(key, rect);
        }

        stripper.extractRegions(page);
        List<DetectedComponent> enriched = new ArrayList<>(layout.components().size());

        for (DetectedComponent component : layout.components()) {
            String key = regionKey(layout.pageIndex(), component.index());
            String raw = stripper.getTextForRegion(key);
            String normalized = normalize(raw);
            if (normalized == null && properties.isOcrEnabled()) {
                normalized = ocrEngine.recognize(mat, component.boundingBox());
            }
            enriched.add(new DetectedComponent(
                    component.index(),
                    component.type(),
                    component.boundingBox(),
                    normalized,
                    component.confidence(),
                    component.widgetType()
            ));
        }

        return new PageLayout(layout.pageIndex(), layout.width(), layout.height(), enriched);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.replaceAll("\\s+", " ");
    }

    private String regionKey(int pageIndex, int componentIndex) {
        return String.format(Locale.ROOT, "p%02d_c%04d", pageIndex, componentIndex);
    }

    private Mat bufferedImageToMat(BufferedImage image) {
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        byte[] data = ((DataBufferByte) converted.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(converted.getHeight(), converted.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }
}
