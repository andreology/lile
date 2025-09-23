package com.contentmanagement.forms.api.service.extraction;

import java.io.IOException;
import java.io.InputStream;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class OpenCvImageLoader {

    private static final Logger log = LoggerFactory.getLogger(OpenCvImageLoader.class);

    private final ResourceLoader resourceLoader;
    private final OpenCvSupport openCvSupport;

    public OpenCvImageLoader(ResourceLoader resourceLoader, OpenCvSupport openCvSupport) {
        this.resourceLoader = resourceLoader;
        this.openCvSupport = openCvSupport;
    }

    public Mat loadClasspathImage(String resourcePath) {
        openCvSupport.ensureLoaded();
        Resource resource = resourceLoader.getResource("classpath:" + resourcePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            MatOfByte mob = new MatOfByte(bytes);
            Mat mat = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
            mob.release();
            if (mat.empty()) {
                throw new IllegalStateException("Failed to decode image: " + resourcePath);
            }
            return mat;
        } catch (IOException ex) {
            log.error("Unable to load image from resource {}", resourcePath, ex);
            throw new IllegalStateException("Unable to load image: " + resourcePath, ex);
        }
    }
}
