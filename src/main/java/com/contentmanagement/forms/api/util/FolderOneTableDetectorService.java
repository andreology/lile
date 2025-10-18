package com.contentmanagement.forms.api.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Service that inspects a PDF page and determines if it contains the
 * "Delivery Package Content (Folder I/1)" table of contents.
 */
public class FolderOneTableDetectorService {

    private static final Pattern HEADER_PATTERN = Pattern.compile(
        "DELIVERY PACKAGE CONTENT\\s+FOLDER\\s+(?:1|I(?![IVXLCDM]))"
    );

    /**
     * Determines whether the given page contains the Folder I table of contents.
     *
     * @param document  the PDF document (must not be {@code null})
     * @param pageIndex zero-based page index
     * @return {@code true} if the page contains the target table header and column headings
     * @throws IllegalArgumentException if the page index is out of bounds
     */
    public boolean containsTarget(PDDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document is required");
        if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
            throw new IllegalArgumentException("pageIndex out of bounds: " + pageIndex);
        }

        List<Row> rows = extractRows(document, pageIndex);
        if (rows.isEmpty()) {
            return false;
        }

        int headerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            String rowText = rows.get(i).normalizedText();
            if (HEADER_PATTERN.matcher(rowText).find()) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            return false;
        }

        Set<String> required = new HashSet<>();
        required.add("CHECK");
        required.add("INCLUDED");
        required.add("TAB");
        required.add("DOCUMENT");
        required.add("FORM");
        required.add("NUMBER");
        required.add("DELIVERY");
        required.add("REQUIREMENT");

        Set<String> seen = new LinkedHashSet<>();
        for (int i = headerIndex + 1; i < rows.size() && i <= headerIndex + 6; i++) {
            seen.addAll(rows.get(i).normalizedWords());
            if (seen.containsAll(required)) {
                return true;
            }
        }
        return false;
    }

    private List<Row> extractRows(PDDocument document, int pageIndex) {
        try {
            PositionCollectingStripper stripper = new PositionCollectingStripper();
            List<TextChunk> chunks = stripper.extract(document, pageIndex);
            if (chunks.isEmpty()) {
                return List.of();
            }
            chunks.sort(Comparator
                .comparingDouble(TextChunk::getY).reversed()
                .thenComparingDouble(TextChunk::getX));

            List<Row> rows = new ArrayList<>();
            for (TextChunk chunk : chunks) {
                if (chunk.text().trim().isEmpty()) {
                    continue;
                }
                Row current = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                if (current == null || !current.accepts(chunk)) {
                    Row newRow = new Row(chunk);
                    rows.add(newRow);
                } else {
                    current.add(chunk);
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to extract text from page " + pageIndex, e);
        }
    }

    private static String normalizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = normalized.toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9]+", " ").trim();
        return normalized;
    }

    private static final class PositionCollectingStripper extends PDFTextStripper {

        private final List<TextChunk> chunks = new ArrayList<>();

        PositionCollectingStripper() throws IOException {
            setSortByPosition(true);
        }

        List<TextChunk> extract(PDDocument document, int pageIndex) throws IOException {
            chunks.clear();
            setStartPage(pageIndex + 1);
            setEndPage(pageIndex + 1);
            getText(document);
            return new ArrayList<>(chunks);
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.trim().isEmpty()) {
                    continue;
                }
                double x = position.getXDirAdj();
                double y = position.getYDirAdj();
                double height = position.getHeightDir();
                double width = position.getWidthDirAdj();
                chunks.add(new TextChunk(unicode, x, y, width, height));
            }
        }
    }

    private record TextChunk(String text, double x, double y, double width, double height) {
        double endX() {
            return x + width;
        }
    }

    private static final class Row {
        private final List<TextChunk> chunks = new ArrayList<>();
        private double baseline;
        private double avgHeight;

        Row(TextChunk chunk) {
            add(chunk);
        }

        boolean accepts(TextChunk chunk) {
            double tolerance = Math.max(avgHeight * 0.6, 3.0);
            return Math.abs(chunk.y() - baseline) <= tolerance;
        }

        void add(TextChunk chunk) {
            chunks.add(chunk);
            baseline = (baseline * (chunks.size() - 1) + chunk.y()) / chunks.size();
            avgHeight = (avgHeight * (chunks.size() - 1) + chunk.height()) / chunks.size();
        }

        double getBaseline() {
            return baseline;
        }

        String normalizedText() {
            return normalizeText(rawText());
        }

        Set<String> normalizedWords() {
            String normalized = normalizedText();
            if (normalized.isEmpty()) {
                return Set.of();
            }
            String[] parts = normalized.split(" ");
            Set<String> words = new HashSet<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    words.add(part);
                }
            }
            return words;
        }

        private String rawText() {
            if (chunks.isEmpty()) {
                return "";
            }
            chunks.sort(Comparator.comparingDouble(TextChunk::x));
            StringBuilder sb = new StringBuilder();
            TextChunk previous = null;
            for (TextChunk chunk : chunks) {
                if (previous != null) {
                    double gap = chunk.x() - previous.endX();
                    double threshold = Math.max(2.0, previous.height() * 0.5);
                    if (gap > threshold) {
                        sb.append(' ');
                    }
                }
                sb.append(chunk.text());
                previous = chunk;
            }
            return sb.toString();
        }
    }
}
