package com.contentmanagement.forms.api.service.extraction;

import java.io.File;
import java.io.IOException;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.leptonica.presets.leptonica;
import org.bytedeco.tesseract.presets.tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NativeTesseractBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NativeTesseractBootstrap.class);
    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    private static volatile boolean loaded;

    private NativeTesseractBootstrap() {
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (NativeTesseractBootstrap.class) {
            if (loaded) {
                return;
            }
            try {
                File leptLibrary = new File(Loader.load(leptonica.class));
                File tessLibrary = new File(Loader.load(tesseract.class));

                registerNativeDirectory(leptLibrary.getParentFile());
                registerNativeDirectory(tessLibrary.getParentFile());
                registerTessdataDirectory(tessLibrary);

                loaded = true;
                log.info("Initialized Tesseract native libraries from {}", tessLibrary.getParentFile());
            } catch (UnsatisfiedLinkError | IOException ex) {
                throw new IllegalStateException("Unable to load Bytedeco native libraries", ex);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Unexpected error while loading native libraries", ex);
            }
        }
    }

    private static void registerNativeDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        appendSystemPath("jna.library.path", directory.getAbsolutePath());
        appendSystemPath("java.library.path", directory.getAbsolutePath());
    }

    private static void registerTessdataDirectory(File tessLibrary) {
        if (System.getProperty("TESSDATA_PREFIX") != null || System.getenv("TESSDATA_PREFIX") != null) {
            return;
        }
        if (tessLibrary == null) {
            return;
        }
        File libDirectory = tessLibrary.getParentFile();
        if (libDirectory == null) {
            return;
        }
        File baseDirectory = libDirectory.getParentFile();
        if (baseDirectory == null) {
            return;
        }
        File tessdata = new File(baseDirectory, "share/tessdata");
        if (tessdata.isDirectory()) {
            System.setProperty("TESSDATA_PREFIX", tessdata.getAbsolutePath());
        }
    }

    private static void appendSystemPath(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String current = System.getProperty(key);
        if (current == null || current.isBlank()) {
            System.setProperty(key, value);
            return;
        }
        String candidate = value + PATH_SEPARATOR + current;
        if (!current.contains(value)) {
            System.setProperty(key, candidate);
        }
    }
}
