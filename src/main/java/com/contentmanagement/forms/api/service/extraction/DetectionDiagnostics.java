package com.contentmanagement.forms.api.service.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.opencv.core.Rect;
import org.slf4j.Logger;

class DetectionDiagnostics {

    private static final double CONFIDENCE_THRESHOLD = 0.5d;

    private final List<LowConfidenceEntry> lowConfidenceEntries = new ArrayList<>();

    void record(PageLayout layout) {
        for (DetectedComponent component : layout.components()) {
            if (component.confidence() < CONFIDENCE_THRESHOLD) {
                lowConfidenceEntries.add(new LowConfidenceEntry(
                        layout.pageIndex(),
                        component.index(),
                        component.type(),
                        component.boundingBox(),
                        component.confidence()));
            }
        }
    }

    void logSummary(Logger logger) {
        if (lowConfidenceEntries.isEmpty()) {
            logger.info("All detected elements met the 50% confidence threshold.");
            return;
        }

        logger.warn("Detected {} element(s) below the 50% confidence threshold:", lowConfidenceEntries.size());
        for (LowConfidenceEntry entry : lowConfidenceEntries) {
            Rect box = entry.boundingBox();
            logger.warn(
                    "  page {} component {} type={} bbox[x={},y={},w={},h={}] confidence={}",
                    entry.pageIndex(),
                    entry.componentIndex(),
                    entry.type(),
                    box.x,
                    box.y,
                    box.width,
                    box.height,
                    String.format(Locale.ROOT, "%.3f", entry.confidence()));
        }
    }

    private record LowConfidenceEntry(
            int pageIndex,
            int componentIndex,
            DetectedComponentType type,
            Rect boundingBox,
            double confidence
    ) {
    }
}
