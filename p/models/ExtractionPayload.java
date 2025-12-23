package com.fnma.rentrollpoc.models;

import java.util.List;
import java.util.Map;

public record ExtractionPayload(
        String documentId,
        Source source,
        List<PagePayload> pages) {

    public record Source(String filename, int pageCount, int dpi, List<String> preprocessSteps, String language) {
    }

    public record PagePayload(
            int pageIndex,
            Size size,
            String layoutHint,
            HeaderLine headerLineCandidate,
            List<ColumnBand> columnBands,
            List<BlockPayload> blocks,
            List<LinePayload> lines,
            List<WordPayload> words,
            Map<String, Object> diagnostics) {
    }

    public record Size(int widthPx, int heightPx, int dpi, double widthNorm, double heightNorm) {
    }

    public record HeaderLine(String lineId, List<String> tokenIds, List<String> matchedHeaders, double confidence) {
    }

    public record ColumnBand(String name, int xStartPx, int xEndPx, double xStartNorm, double xEndNorm) {
    }

    public record BlockPayload(String id, BBox bboxPx, BBox bboxNorm, String type, double confidence,
                               List<String> lineIds) {
    }

    public record LinePayload(String id, String text, BBox bboxPx, BBox bboxNorm, List<String> wordIds) {
    }

    public record WordPayload(String id, String text, BBox bboxPx, BBox bboxNorm,
                              boolean isNumeric, boolean isDate, boolean isCurrency) {
    }

    public record BBox(double x, double y, double w, double h) {
    }
}
