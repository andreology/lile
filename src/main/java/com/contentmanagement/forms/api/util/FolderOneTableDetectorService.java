package com.contentmanagement.forms.api.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Service that inspects a PDF page and determines if it contains the
 * "Delivery Package Content (Folder I/1)" table of contents.
 */
public class FolderOneTableDetectorService {

    private static final Pattern FOLDER_ONE_PATTERN = Pattern.compile(
        "DELIVERY PACKAGE CONTENT\\s*\\(FOLDER\\s*(?:1|I(?![IVXLCDM])))"
    );
    private static final Pattern CHECK_PATTERN = Pattern.compile("CHECK\\s+IF\\s+INCLUDED");
    private static final Pattern FORM_NUMBER_PATTERN = Pattern.compile("FORM\\s+NUMBER");
    private static final Pattern DELIVERY_REQUIREMENT_PATTERN = Pattern.compile("DELIVERY\\s+REQUIREMENT");
    private static final Pattern TAB_WORD_PATTERN = Pattern.compile("\\bTAB\\b");
    private static final Pattern DOCUMENT_WORD_PATTERN = Pattern.compile("\\bDOCUMENT\\b");

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

        String pageText = extractPageText(document, pageIndex);
        String normalized = normalize(pageText);
        String flat = normalized.replace('\n', ' ').replace('\r', ' ');
        flat = flat.replaceAll("\\s+", " ").trim();

        boolean hasHeader = FOLDER_ONE_PATTERN.matcher(flat).find();
        if (!hasHeader) {
            return false;
        }

        boolean hasCheck = CHECK_PATTERN.matcher(flat).find();
        boolean hasFormNumber = FORM_NUMBER_PATTERN.matcher(flat).find();
        boolean hasDeliveryRequirement = DELIVERY_REQUIREMENT_PATTERN.matcher(flat).find();
        boolean hasTab = TAB_WORD_PATTERN.matcher(flat).find();
        boolean hasDocument = DOCUMENT_WORD_PATTERN.matcher(flat).find();

        return hasCheck && hasFormNumber && hasDeliveryRequirement && hasTab && hasDocument;
    }

    private String extractPageText(PDDocument document, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to extract text from page " + pageIndex, e);
        }
    }

    private String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = normalized.toUpperCase(Locale.ROOT);
        return normalized;
    }
}
