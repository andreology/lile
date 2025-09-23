package com.contentmanagement.forms.api.service.extraction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.contentmanagement.forms.api.config.FormProcessingProperties;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

@Component
public class TesseractOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);
    private static final String TRAINED_DATA_GLOB = "**/*.traineddata";

    private final FormProcessingProperties properties;
    private final ResourceLoader resourceLoader;

    private final ThreadLocal<ITesseract> tesseractThreadLocal = ThreadLocal.withInitial(this::createConfiguredInstance);
    private final AtomicReference<Path> resolvedTessDataDirectory = new AtomicReference<>();

    public TesseractOcrEngine(FormProcessingProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    public String recognize(Mat source, Rect region) {
        if (!properties.isOcrEnabled()) {
            return null;
        }

        Rect clipped = clip(region, source.width(), source.height());
        if (clipped.width <= 0 || clipped.height <= 0) {
            return null;
        }

        Mat roi = new Mat(source, clipped);
        try {
            Mat prepared = preprocess(roi);
            try {
                BufferedImage image = matToBufferedImage(prepared);
                return doOcr(image);
            } finally {
                prepared.release();
            }
        } finally {
            roi.release();
        }
    }

    private ITesseract createConfiguredInstance() {
        Path tessDataDir = resolveTessDataPath();
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataDir.toAbsolutePath().toString());
        tesseract.setLanguage(properties.getOcrLanguage());
        tesseract.setPageSegMode(6); // Assume a uniform block of text.
        tesseract.setOcrEngineMode(1); // LSTM only.
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        return tesseract;
    }

    private Path resolveTessDataPath() {
        Path cached = resolvedTessDataDirectory.get();
        if (cached != null) {
            return cached;
        }

        synchronized (resolvedTessDataDirectory) {
            cached = resolvedTessDataDirectory.get();
            if (cached != null) {
                return cached;
            }

            String location = properties.getTessDataPath();
            if (location == null || location.isBlank()) {
                throw new IllegalStateException("Tesseract tessdata path is not configured");
            }

            try {
                if (location.startsWith("classpath:")) {
                    Path directory = unpackClasspathTessData(location);
                    resolvedTessDataDirectory.set(directory);
                    return directory;
                }

                Resource resource = resourceLoader.getResource(location);
                if (!resource.exists()) {
                    throw new IllegalStateException("Configured tessdata directory does not exist: " + location);
                }
                Path directory = resource.getFile().toPath();
                resolvedTessDataDirectory.set(directory);
                return directory;
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to resolve tessdata directory", ex);
            }
        }
    }

    private Path unpackClasspathTessData(String location) throws IOException {
        String base = location.substring("classpath:".length());
        if (base.startsWith("/")) {
            base = base.substring(1);
        }

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
        String pattern = String.format(Locale.ROOT, "classpath*:%s/%s", base, TRAINED_DATA_GLOB);
        Resource[] resources = resolver.getResources(pattern);
        if (resources.length == 0) {
            throw new IllegalStateException("No *.traineddata resources found under " + location);
        }

        Path tempDir = Files.createTempDirectory("tessdata-");
        tempDir.toFile().deleteOnExit();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || filename.isBlank()) {
                continue;
            }
            Path target = tempDir.resolve(filename);
            Files.copy(resource.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }

        return tempDir;
    }

    private String doOcr(BufferedImage image) {
        try {
            String raw = tesseractThreadLocal.get().doOCR(image);
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().replaceAll("\\s+", " ");
            return normalized.isEmpty() ? null : normalized;
        } catch (TesseractException ex) {
            log.warn("OCR failed", ex);
            return null;
        }
    }

    private Mat preprocess(Mat roi) {
        Mat gray = new Mat();
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);

        Mat scaled = new Mat();
        Imgproc.resize(gray, scaled, new Size(), 2.0, 2.0, Imgproc.INTER_LINEAR);

        Mat denoised = new Mat();
        Imgproc.bilateralFilter(scaled, denoised, 7, 60, 60);

        Mat binary = new Mat();
        Imgproc.threshold(denoised, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);

        gray.release();
        scaled.release();
        denoised.release();
        kernel.release();

        return binary;
    }

    private BufferedImage matToBufferedImage(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();

        int imageType = channels > 1 ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        BufferedImage image = new BufferedImage(width, height, imageType);
        byte[] data = new byte[width * height * channels];
        mat.get(0, 0, data);
        image.getRaster().setDataElements(0, 0, width, height, data);
        return image;
    }

    private Rect clip(Rect rect, double maxWidth, double maxHeight) {
        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int width = Math.min(rect.width, (int) Math.max(1, maxWidth - x));
        int height = Math.min(rect.height, (int) Math.max(1, maxHeight - y));
        return new Rect(x, y, width, height);
    }
}
