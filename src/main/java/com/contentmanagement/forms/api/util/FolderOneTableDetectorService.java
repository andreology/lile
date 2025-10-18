package com.contentmanagement.forms.api.util;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Detects the page range that contains the “Delivery Package Content (Folder I)” table of contents.
 * <p>
 * The detector analyses one page at a time, normalises the extracted text, and evaluates a collection
 * of lightweight heuristics derived from the table’s header and body vocabulary. No positional
 * assumptions or colour cues are required, making the detector resilient to OCR quirks.
 */
public final class FolderOneTableDetectorService {

    private static final Logger LOGGER = Logger.getLogger(FolderOneTableDetectorService.class.getName());

    private static final String[][] HEADER_GROUPS = {
        {"check", "if", "included"},
        {"tab"},
        {"document"},
        {"form", "number"},
        {"delivery", "requirement"}
    };

    private static final Set<String> BODY_VOCABULARY = Set.of(
        "schedule", "exhibit", "series", "recorded", "original", "electronic", "copy"
    );

    private static final Pattern FORM_NUMBER_PATTERN = Pattern.compile("\\b6\\d{3}\\b");

    private FolderOneTableDetectorService() {
        // no instances
    }

    /**
     * Evaluates whether the specified page contains the Folder I table of contents.
     *
     * @param document  the PDF document (not closed by this method)
     * @param pageIndex zero-based page index
     * @return {@code true} if the page satisfies the Folder I heuristics, {@code false} otherwise
     * @throws IOException if PDF text extraction fails
     */
    public static boolean containsTarget(PDDocument document, int pageIndex) throws IOException {
        Objects.requireNonNull(document, "document is required");
        if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
            throw new IllegalArgumentException("pageIndex out of bounds: " + pageIndex);
        }

        String pageText = extractPageText(document, pageIndex);
        String normalized = normalize(pageText);
        Set<String> tokens = tokensFrom(normalized);

        int headerCoverage = headerCoverage(tokens);
        boolean titleI = normalized.contains("delivery package content folder i");
        boolean titleIII = normalized.contains("delivery package content folder iii");
        int formHits = Math.min(countRegex(pageText, FORM_NUMBER_PATTERN), 6);
        int bodyHits = Math.min(countVocabulary(tokens), 6);
        int score = 3 * (titleI ? 1 : 0)
            + 2 * Math.min(headerCoverage, 5)
            + formHits
            + bodyHits;

        LOGGER.log(Level.FINE,
            () -> String.format(
                "Page %d metrics -> header=%d titleI=%s titleIII=%s formHits=%d bodyHits=%d score=%d",
                pageIndex, headerCoverage, titleI, titleIII, formHits, bodyHits, score));

        if (titleIII) {
            return false;
        }

        boolean strongHeader = headerCoverage >= 3;
        boolean strongForms = formHits >= 2;
        boolean strongScore = score >= 9;

        return titleI || (strongHeader && strongForms) || strongScore;
    }

    private static int headerCoverage(Set<String> tokens) {
        int coverage = 0;
        for (String[] group : HEADER_GROUPS) {
            if (hasAll(tokens, group)) {
                coverage++;
            }
        }
        return coverage;
    }

    private static int countVocabulary(Set<String> tokens) {
        int hits = 0;
        for (String vocab : BODY_VOCABULARY) {
            if (tokens.contains(vocab)) {
                hits++;
            }
        }
        return hits;
    }

    private static String extractPageText(PDDocument document, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document);
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("[^a-z]+", " ");
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.trim();
    }

    private static Set<String> tokensFrom(String normalized) {
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        String[] parts = normalized.split(" ");
        Set<String> set = new HashSet<>(parts.length * 2);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            set.add(part);
            if (part.length() > 1 && part.endsWith("s")) {
                set.add(part.substring(0, part.length() - 1));
            }
        }
        return set;
    }

    private static boolean hasAll(Set<String> tokens, String... words) {
        for (String word : words) {
            if (!tokens.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAny(Set<String> tokens, String... words) {
        for (String word : words) {
            if (tokens.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static int countRegex(String raw, Pattern pattern) {
        Matcher matcher = pattern.matcher(raw);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

}
