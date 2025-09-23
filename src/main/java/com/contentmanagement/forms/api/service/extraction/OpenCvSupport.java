package com.contentmanagement.forms.api.service.extraction;

import java.util.concurrent.atomic.AtomicBoolean;
import nu.pattern.OpenCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenCvSupport {

    private static final Logger log = LoggerFactory.getLogger(OpenCvSupport.class);

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public void ensureLoaded() {
        if (initialized.compareAndSet(false, true)) {
            try {
                OpenCV.loadShared();
                log.info("OpenCV native libraries loaded");
            } catch (UnsatisfiedLinkError error) {
                initialized.set(false);
                log.error("Failed to load OpenCV native libraries", error);
                throw error;
            }
        }
    }
}
