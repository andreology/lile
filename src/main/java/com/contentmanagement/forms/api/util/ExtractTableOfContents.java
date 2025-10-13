package com.contentmanagement.forms.api.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class ExtractTableOfContents {

    private static final double EDGE_TOLERANCE_FACTOR = 0.35;
    private static final double MIN_EDGE_TOLERANCE = 3.0;
    private static final double RANGE_TOLERANCE = 4.0;
    private static final int OCR_MARGIN = 2;
    private static final double CHECKBOX_ASPECT_TOL = 0.35;
    private static final double CHECKBOX_MIN_AREA = 36.0;
    private static final double CHECKBOX_INK_RATIO = 0.08;
    private static final int MAX_CELL_WIDTH = 42;

    private final Tesseract OCREngine;

    public ExtractTableOfContents(Tesseract ocrEngine) {
        this.OCREngine = Objects.requireNonNull(ocrEngine, "OCREngine");
    }

    public List<RowRecord> extract(Mat cellLinesMat,
                                   List<MatOfPoint> cellContours,
                                   Mat contentMat,
                                   List<MatOfPoint> contentContours) {
        Objects.requireNonNull(cellLinesMat, "cellLinesMat");
        Objects.requireNonNull(cellContours, "cellContours");
        Objects.requireNonNull(contentMat, "contentMat");
        Objects.requireNonNull(contentContours, "contentContours");

        TableGrid grid = TableGrid.fromContours(cellContours);
        if (grid.isEmpty()) {
            return Collections.emptyList();
        }

        Mat gray = toGray(contentMat);
        assignContent(grid, contentMat, gray, contentContours);
        gray.release();

        ColumnSlices slices = ColumnSlices.build(grid);
        List<RowRecord> records = buildRecords(grid, slices);
        return records;
    }

    public static void printRecordsAsTable(List<RowRecord> records) {
        if (records == null || records.isEmpty()) {
            System.out.println("(no rows)");
            return;
        }

        String[] headers = {"Included", "Tab", "Document", "Form Number", "Delivery Requirement", "Header", "Section"};
        List<String[]> rows = new ArrayList<>();
        List<Boolean> spanRow = new ArrayList<>();
        rows.add(headers);
        spanRow.add(false);
        for (RowRecord record : records) {
            rows.add(new String[]{
                record.isIncluded() ? "✔" : "",
                wrapCell(record.getTab()),
                wrapCell(record.getDocument()),
                wrapCell(record.getFormNumber()),
                wrapCell(record.getDeliveryRequirement()),
                record.isHeaderRow() ? "H" : "",
                record.isSectionRow() ? "S" : ""
            });
            spanRow.add(record.isHeaderRow() || record.isSectionRow());
        }

        int[] widths = new int[headers.length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], measureMaxWidth(row[i]));
            }
        }

        String border = buildBorder(widths);
        int fullWidth = border.length() - 2;
        System.out.println(border);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            boolean span = spanRow.get(i);
            if (span && i > 0) {
                String[] lines = row[2] == null ? new String[]{""} : row[2].split("\\R");
                if (lines.length == 0) {
                    lines = new String[]{""};
                }
                for (String line : lines) {
                    String padded = pad(line, fullWidth);
                    System.out.println("|" + padded + "|");
                }
                System.out.println(border);
                continue;
            }

            List<String[]> wrapped = wrapRow(row, widths);
            for (String[] line : wrapped) {
                StringBuilder out = new StringBuilder("|");
                for (int col = 0; col < line.length; col++) {
                    out.append(" ").append(pad(line[col], widths[col])).append(" |");
                }
                System.out.println(out);
            }
            System.out.println(border);
        }
    }

    private static String wrapCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        List<String> wrapped = new ArrayList<>();
        for (String line : value.split("\\R")) {
            wrapped.addAll(wrapLine(line, MAX_CELL_WIDTH));
        }
        return String.join("\n", wrapped);
    }

    private static List<String> wrapLine(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > width) {
            int breakPos = remaining.lastIndexOf(' ', width);
            if (breakPos <= 0) {
                breakPos = width;
            }
            lines.add(remaining.substring(0, breakPos).trim());
            remaining = remaining.substring(breakPos).trim();
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static int measureMaxWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (String line : text.split("\\R")) {
            width = Math.max(width, line.length());
        }
        return width;
    }

    private static List<String[]> wrapRow(String[] row, int[] widths) {
        List<String[]> lines = new ArrayList<>();
        int maxLines = 0;
        List<String[]> splitColumns = new ArrayList<>();
        for (int i = 0; i < row.length; i++) {
            String value = row[i] == null ? "" : row[i];
            String[] split = value.split("\\R");
            splitColumns.add(split);
            maxLines = Math.max(maxLines, split.length);
        }
        for (int line = 0; line < maxLines; line++) {
            String[] out = new String[row.length];
            for (int col = 0; col < row.length; col++) {
                String[] split = splitColumns.get(col);
                out[col] = line < split.length ? split[line] : "";
            }
            lines.add(out);
        }
        if (lines.isEmpty()) {
            lines.add(new String[row.length]);
        }
        return lines;
    }

    private static String buildBorder(int[] widths) {
        StringBuilder builder = new StringBuilder();
        builder.append("+");
        for (int width : widths) {
            builder.append("-");
            for (int i = 0; i < width; i++) {
                builder.append("-");
            }
            builder.append("-");
            builder.append("+");
        }
        return builder.toString();
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    public static final class RowRecord {
        private final boolean included;
        private final String tab;
        private final String document;
        private final String formNumber;
        private final String deliveryRequirement;
        private final boolean headerRow;
        private final boolean sectionRow;

        RowRecord(boolean included,
                  String tab,
                  String document,
                  String formNumber,
                  String deliveryRequirement,
                  boolean headerRow,
                  boolean sectionRow) {
            this.included = included;
            this.tab = textOrEmpty(tab);
            this.document = textOrEmpty(document);
            this.formNumber = textOrEmpty(formNumber);
            this.deliveryRequirement = textOrEmpty(deliveryRequirement);
            this.headerRow = headerRow;
            this.sectionRow = sectionRow;
        }

        public boolean isIncluded() {
            return included;
        }

        public String getTab() {
            return tab;
        }

        public String getDocument() {
            return document;
        }

        public String getFormNumber() {
            return formNumber;
        }

        public String getDeliveryRequirement() {
            return deliveryRequirement;
        }

        public boolean isHeaderRow() {
            return headerRow;
        }

        public boolean isSectionRow() {
            return sectionRow;
        }

        @Override
        public String toString() {
            return "RowRecord{" +
                "included=" + included +
                ", tab='" + tab + '\'' +
                ", document='" + document + '\'' +
                ", formNumber='" + formNumber + '\'' +
                ", deliveryRequirement='" + deliveryRequirement + '\'' +
                ", headerRow=" + headerRow +
                ", sectionRow=" + sectionRow +
                '}';
        }

        private static String textOrEmpty(String text) {
            return text == null ? "" : text;
        }
    }

    private enum ColumnType {
        CHECK_INCLUDED,
        TAB,
        DOCUMENT,
        FORM_NUMBER,
        DELIVERY_REQUIREMENT
    }

    private List<RowRecord> buildRecords(TableGrid grid, ColumnSlices slices) {
        List<ContentSlice> tabSlices = new ArrayList<>(slices.tabSlices);
        List<ContentSlice> documentSlices = new ArrayList<>(slices.documentSlices);
        List<ContentSlice> formSlices = new ArrayList<>(slices.formSlices);
        List<ContentSlice> deliverySlices = new ArrayList<>(slices.deliverySlices);
        List<CheckboxSlice> checkSlices = new ArrayList<>(slices.checkSlices);

        int docIndex = 0;
        int formIndex = 0;
        int deliveryIndex = 0;
        int checkIndex = 0;
        int tabIndex = 0;
        ContentSlice activeTab = tabSlices.isEmpty() ? null : tabSlices.get(tabIndex);

        List<OrderedRecord> ordered = new ArrayList<>();

        while (docIndex < documentSlices.size()) {
            ContentSlice docSlice = documentSlices.get(docIndex);
            if (docSlice.text.isBlank()) {
                docIndex++;
                continue;
            }

            BannerType banner = classifyBanner(docSlice, grid);
            if (banner != BannerType.NONE) {
                boolean isHeader = banner == BannerType.HEADER;
                boolean isSection = banner == BannerType.SECTION;
                RowRecord record = new RowRecord(false,
                    "",
                    docSlice.text,
                    "",
                    "",
                    isHeader,
                    isSection);
                ordered.add(new OrderedRecord(record, docSlice.getVisualTop()));
                docIndex++;
                continue;
            }

            double docTop = docSlice.top;
            double docBottom = docSlice.bottom;
            double docCenter = (docTop + docBottom) / 2.0;

            // Align tab
            if (activeTab != null) {
                while (activeTab != null && docCenter > activeTab.getCellBottom() + RANGE_TOLERANCE && tabIndex + 1 < tabSlices.size()) {
                    tabIndex++;
                    activeTab = tabSlices.get(tabIndex);
                }
                if (activeTab != null && docCenter < activeTab.getCellTop() - RANGE_TOLERANCE && tabIndex + 1 < tabSlices.size()) {
                    while (activeTab != null && docCenter < activeTab.getCellTop() - RANGE_TOLERANCE && tabIndex + 1 < tabSlices.size()) {
                        tabIndex++;
                        activeTab = tabSlices.get(tabIndex);
                    }
                }
            }
            String tabText = "";
            if (activeTab != null && rangesOverlap(activeTab.getCellTop(), activeTab.getCellBottom(), docTop, docBottom)) {
                tabText = activeTab.text;
            }

            // Align checkbox
            CheckboxSlice matchedCheck = null;
            while (checkIndex < checkSlices.size()) {
                CheckboxSlice candidate = checkSlices.get(checkIndex);
                if (candidate.bottom < docTop - RANGE_TOLERANCE) {
                    checkIndex++;
                    continue;
                }
                if (candidate.top > docBottom + RANGE_TOLERANCE) {
                    break;
                }
                if (rangeContains(candidate.top, candidate.bottom, docTop, docBottom) || pointWithin(candidate.center(), docTop, docBottom)) {
                    matchedCheck = candidate;
                    checkIndex++;
                }
                break;
            }
            boolean included = matchedCheck != null && matchedCheck.checked;

            // Form number
            SliceResult formResult = consumeContentWithin(formSlices, formIndex, docTop, docBottom);
            ContentSlice formSlice = formResult.slice;
            formIndex = formResult.nextIndex;

            // Delivery requirement
            SliceResult deliveryResult = consumeContentWithin(deliverySlices, deliveryIndex, docTop, docBottom);
            ContentSlice deliverySlice = deliveryResult.slice;
            deliveryIndex = deliveryResult.nextIndex;

            RowRecord record = new RowRecord(included,
                tabText,
                docSlice.text,
                formSlice != null ? formSlice.text : "",
                deliverySlice != null ? deliverySlice.text : "",
                false,
                false);

            ordered.add(new OrderedRecord(record, docSlice.getVisualTop()));
            docIndex++;
        }

        ordered.sort(Comparator.comparingDouble(o -> o.visualTop));
        List<RowRecord> records = new ArrayList<>(ordered.size());
        for (OrderedRecord holder : ordered) {
            records.add(holder.record);
        }
        return records;
    }

    private static SliceResult consumeContentWithin(List<ContentSlice> slices, int startIndex, double docTop, double docBottom) {
        int index = startIndex;
        while (index < slices.size()) {
            ContentSlice slice = slices.get(index);
            if (slice.bottom <= docTop - RANGE_TOLERANCE) {
                index++;
                continue;
            }
            if (slice.top >= docBottom + RANGE_TOLERANCE) {
                return new SliceResult(null, index);
            }
            double overlap = overlapAmount(docTop, docBottom, slice.top, slice.bottom);
            double sliceHeight = Math.max(1.0, slice.bottom - slice.top);
            if (rangeContains(docTop, docBottom, slice.top, slice.bottom) || (overlap / sliceHeight) >= 0.65) {
                return new SliceResult(slice, index + 1);
            }
            return new SliceResult(null, index);
        }
        return new SliceResult(null, index);
    }

    private static BannerType classifyBanner(ContentSlice slice, TableGrid grid) {
        String text = slice.text;
        String normalizedUpper = text.toUpperCase(Locale.ROOT);
        boolean fullSpan = slice.cell.colStart == 0 && slice.cell.colEnd >= grid.colCount - 1;
        if (text.contains("DELIVERY PACKAGE CONTENT")) {
            return BannerType.HEADER;
        }
        if (normalizedUpper.equals(text) && text.length() > 4 && fullSpan) {
            return BannerType.SECTION;
        }
        if (normalizedUpper.equals(text) && text.contains("DOCUMENT") && slice.cell.colStart <= ColumnType.DOCUMENT.ordinal()) {
            return BannerType.SECTION;
        }
        return BannerType.NONE;
    }

    private void assignContent(TableGrid grid,
                               Mat contentMat,
                               Mat gray,
                               List<MatOfPoint> contentContours) {
        for (MatOfPoint contour : contentContours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (!intersects(rect, grid.bounds, 3)) {
                continue;
            }
            double centerX = rect.x + rect.width / 2.0;
            double centerY = rect.y + rect.height / 2.0;
            int rowIndex = locateInterval(grid.rowEdges, centerY);
            int colIndex = locateInterval(grid.colEdges, centerX);
            if (rowIndex < 0 || rowIndex >= grid.rowCount || colIndex < 0 || colIndex >= grid.colCount) {
                continue;
            }
            ColumnType columnType = ColumnType.values()[Math.min(colIndex, ColumnType.values().length - 1)];
            Cell cell = grid.cellAt(rowIndex, columnType);
            if (cell == null) {
                continue;
            }

            if (isCheckboxCandidate(rect)) {
                boolean checked = isCheckboxMarked(gray, rect);
                cell.addCheckbox(new CheckboxFragment(rect, checked));
                continue;
            }

            String ocr;
            if (columnType == ColumnType.TAB) {
                System.out.println("[ExtractTableOfContents] OCR tab ROI: " + rect);
                ocr = performOcr(contentMat, rect);
                System.out.println("[ExtractTableOfContents] OCR tab result: '" + ocr + "'");
            } else {
                ocr = performOcr(contentMat, rect);
            }
            if (ocr.isBlank()) {
                continue;
            }
            cell.addFragment(new TextFragment(rect, ocr));
        }
    }

    private Mat toGray(Mat source) {
        Mat gray = new Mat();
        if (source.channels() == 1) {
            source.copyTo(gray);
        } else if (source.channels() == 3) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY);
        }
        return gray;
    }

    private String performOcr(Mat source, Rect rect) {
        Rect roi = clampRect(rect, source, OCR_MARGIN);
        Mat patch = new Mat(source, roi).clone();
        BufferedImage image = matToBufferedImage(patch);
        patch.release();
        try {
            String raw = OCREngine.doOCR(image);
            return normalize(raw);
        } catch (TesseractException e) {
            return "";
        }
    }

    private static Rect clampRect(Rect rect, Mat mat, int margin) {
        int x = Math.max(0, rect.x - margin);
        int y = Math.max(0, rect.y - margin);
        int right = Math.min(mat.cols(), rect.x + rect.width + margin);
        int bottom = Math.min(mat.rows(), rect.y + rect.height + margin);
        return new Rect(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private static BufferedImage matToBufferedImage(Mat mat) {
        Mat converted = new Mat();
        if (mat.channels() == 1) {
            Imgproc.cvtColor(mat, converted, Imgproc.COLOR_GRAY2BGR);
        } else if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, converted, Imgproc.COLOR_BGRA2BGR);
        } else {
            mat.copyTo(converted);
        }
        int width = converted.cols();
        int height = converted.rows();
        int channels = converted.channels();
        byte[] source = new byte[width * height * channels];
        converted.get(0, 0, source);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(source, 0, target, 0, source.length);
        converted.release();
        return image;
    }

    private static boolean intersects(Rect rect, Rect bounds, int margin) {
        return rect.x + rect.width > bounds.x - margin &&
            rect.x < bounds.x + bounds.width + margin &&
            rect.y + rect.height > bounds.y - margin &&
            rect.y < bounds.y + bounds.height + margin;
    }

    private static boolean isCheckboxCandidate(Rect rect) {
        if (rect.width <= 0 || rect.height <= 0) {
            return false;
        }
        double area = rect.width * rect.height;
        if (area < CHECKBOX_MIN_AREA) {
            return false;
        }
        double aspect = rect.width / (double) rect.height;
        return aspect > (1.0 - CHECKBOX_ASPECT_TOL) && aspect < (1.0 + CHECKBOX_ASPECT_TOL);
    }

    private static boolean isCheckboxMarked(Mat gray, Rect rect) {
        Rect roi = clampRect(rect, gray, 1);
        Mat sub = new Mat(gray, roi);
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(sub, blurred, new Size(3, 3), 0);
        Mat binary = new Mat();
        Imgproc.threshold(blurred, binary, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);
        double ink = Core.sumElems(binary).val[0] / 255.0;
        double pixels = binary.total();
        double ratio = pixels == 0 ? 0 : ink / pixels;
        sub.release();
        blurred.release();
        binary.release();
        return ratio >= CHECKBOX_INK_RATIO;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] lines = trimmed.split("\\R+");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(normalized);
        }
        return builder.toString();
    }

    private static boolean rangeContains(double containerTop, double containerBottom, double itemTop, double itemBottom) {
        return itemTop >= containerTop - RANGE_TOLERANCE && itemBottom <= containerBottom + RANGE_TOLERANCE;
    }

    private static boolean rangesOverlap(double topA, double bottomA, double topB, double bottomB) {
        return Math.max(topA, topB) <= Math.min(bottomA, bottomB);
    }

    private static double overlapAmount(double topA, double bottomA, double topB, double bottomB) {
        double top = Math.max(topA, topB);
        double bottom = Math.min(bottomA, bottomB);
        return Math.max(0.0, bottom - top);
    }

    private static boolean pointWithin(double point, double rangeTop, double rangeBottom) {
        return point >= rangeTop - RANGE_TOLERANCE && point <= rangeBottom + RANGE_TOLERANCE;
    }

    private static int locateInterval(double[] edges, double value) {
        if (edges.length < 2) {
            return -1;
        }
        if (value < edges[0]) {
            return 0;
        }
        if (value >= edges[edges.length - 1]) {
            return edges.length - 2;
        }
        for (int i = 0; i < edges.length - 1; i++) {
            if (value >= edges[i] && value < edges[i + 1]) {
                return i;
            }
        }
        return edges.length - 2;
    }

    private enum BannerType {
        NONE,
        HEADER,
        SECTION
    }

    private static final class SliceResult {
        final ContentSlice slice;
        final int nextIndex;

        SliceResult(ContentSlice slice, int nextIndex) {
            this.slice = slice;
            this.nextIndex = nextIndex;
        }
    }

    private static final class OrderedRecord {
        final RowRecord record;
        final double visualTop;

        OrderedRecord(RowRecord record, double visualTop) {
            this.record = record;
            this.visualTop = visualTop;
        }
    }

    private static final class ColumnSlices {
        final List<ContentSlice> tabSlices;
        final List<ContentSlice> documentSlices;
        final List<ContentSlice> formSlices;
        final List<ContentSlice> deliverySlices;
        final List<CheckboxSlice> checkSlices;

        ColumnSlices(List<ContentSlice> tabSlices,
                     List<ContentSlice> documentSlices,
                     List<ContentSlice> formSlices,
                     List<ContentSlice> deliverySlices,
                     List<CheckboxSlice> checkSlices) {
            this.tabSlices = tabSlices;
            this.documentSlices = documentSlices;
            this.formSlices = formSlices;
            this.deliverySlices = deliverySlices;
            this.checkSlices = checkSlices;
        }

        static ColumnSlices build(TableGrid grid) {
            Map<ColumnType, List<ContentSlice>> textSlices = new EnumMap<>(ColumnType.class);
            for (ColumnType type : ColumnType.values()) {
                if (type == ColumnType.CHECK_INCLUDED) {
                    continue;
                }
                textSlices.put(type, collectTextSlices(grid, type));
            }
            List<CheckboxSlice> checkSlices = collectCheckboxSlices(grid);
            return new ColumnSlices(
                textSlices.getOrDefault(ColumnType.TAB, Collections.emptyList()),
                textSlices.getOrDefault(ColumnType.DOCUMENT, Collections.emptyList()),
                textSlices.getOrDefault(ColumnType.FORM_NUMBER, Collections.emptyList()),
                textSlices.getOrDefault(ColumnType.DELIVERY_REQUIREMENT, Collections.emptyList()),
                checkSlices
            );
        }

        private static List<ContentSlice> collectTextSlices(TableGrid grid, ColumnType column) {
            List<Cell> cells = grid.cellsForColumn(column);
            List<ContentSlice> slices = new ArrayList<>();
            for (Cell cell : cells) {
                List<TextFragment> fragments = new ArrayList<>(cell.fragments);
                fragments.sort(Comparator.comparingInt(fragment -> fragment.rect.y));
                if (fragments.isEmpty()) {
                    if (column == ColumnType.TAB) {
                        double top = cell.bounds.y;
                        double bottom = cell.bounds.y + cell.bounds.height;
                        slices.add(new ContentSlice(cell, "", top, bottom, new Rect(cell.bounds.x, cell.bounds.y, cell.bounds.width, cell.bounds.height)));
                    }
                    continue;
                }
                for (int i = 0; i < fragments.size(); i++) {
                    TextFragment fragment = fragments.get(i);
                    Rect rect = fragment.rect;
                    double top = rect.y;
                    double nextTop = cell.bounds.y + cell.bounds.height;
                    if (i + 1 < fragments.size()) {
                        nextTop = fragments.get(i + 1).rect.y;
                    }
                    double bottom = Math.max(rect.y + rect.height, nextTop);
                    bottom = Math.min(bottom, cell.bounds.y + cell.bounds.height);
                    slices.add(new ContentSlice(cell, fragment.text, top, bottom, rect));
                }
            }
            slices.sort(Comparator.comparingDouble(slice -> slice.top));
            return slices;
        }

        private static List<CheckboxSlice> collectCheckboxSlices(TableGrid grid) {
            List<Cell> cells = grid.cellsForColumn(ColumnType.CHECK_INCLUDED);
            List<CheckboxSlice> slices = new ArrayList<>();
            for (Cell cell : cells) {
                List<CheckboxFragment> fragments = new ArrayList<>(cell.checkboxes);
                fragments.sort(Comparator.comparingInt(fragment -> fragment.rect.y));
                for (int i = 0; i < fragments.size(); i++) {
                    CheckboxFragment fragment = fragments.get(i);
                    Rect rect = fragment.rect;
                    double top = rect.y;
                    double nextTop = cell.bounds.y + cell.bounds.height;
                    if (i + 1 < fragments.size()) {
                        nextTop = fragments.get(i + 1).rect.y;
                    }
                    double bottom = Math.max(rect.y + rect.height, nextTop);
                    bottom = Math.min(bottom, cell.bounds.y + cell.bounds.height);
                    slices.add(new CheckboxSlice(cell, fragment.checked, top, bottom, rect));
                }
            }
            slices.sort(Comparator.comparingDouble(slice -> slice.top));
            return slices;
        }
    }

    private static final class ContentSlice {
        final Cell cell;
        final String text;
        final double top;
        final double bottom;
        final Rect rect;

        ContentSlice(Cell cell, String text, double top, double bottom, Rect rect) {
            this.cell = cell;
            this.text = text == null ? "" : text;
            this.top = top;
            this.bottom = Math.max(bottom, top + 1.0);
            this.rect = rect;
        }

        double getCellTop() {
            return cell.bounds.y;
        }

        double getCellBottom() {
            return cell.bounds.y + cell.bounds.height;
        }

        double getVisualTop() {
            return rect.y;
        }
    }

    private static final class CheckboxSlice {
        final Cell cell;
        final boolean checked;
        final double top;
        final double bottom;
        final Rect rect;

        CheckboxSlice(Cell cell, boolean checked, double top, double bottom, Rect rect) {
            this.cell = cell;
            this.checked = checked;
            this.top = top;
            this.bottom = Math.max(bottom, top + 1.0);
            this.rect = rect;
        }

        double center() {
            return (top + bottom) / 2.0;
        }
    }

    private static final class TableGrid {
        final double[] rowEdges;
        final double[] colEdges;
        final int rowCount;
        final int colCount;
        final Rect bounds;
        final Cell[][] matrix;
        final List<Cell> cells;

        static TableGrid fromContours(List<MatOfPoint> contours) {
            List<Rect> rects = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                if (rect.width > 0 && rect.height > 0) {
                    rects.add(rect);
                }
            }
            if (rects.isEmpty()) {
                return new TableGrid(new double[0], new double[0], new Rect(), new Cell[0][0], new ArrayList<>());
            }

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE;
            double maxY = Double.MIN_VALUE;
            double[] heights = new double[rects.size()];
            double[] widths = new double[rects.size()];
            List<Double> rowEdges = new ArrayList<>();
            List<Double> colEdges = new ArrayList<>();

            for (int i = 0; i < rects.size(); i++) {
                Rect rect = rects.get(i);
                heights[i] = rect.height;
                widths[i] = rect.width;
                double top = rect.y;
                double bottom = rect.y + rect.height;
                double left = rect.x;
                double right = rect.x + rect.width;
                rowEdges.add(top);
                rowEdges.add(bottom);
                colEdges.add(left);
                colEdges.add(right);
                minX = Math.min(minX, left);
                minY = Math.min(minY, top);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, bottom);
            }

            double medianHeight = median(heights);
            double medianWidth = median(widths);
            double rowTolerance = Math.max(MIN_EDGE_TOLERANCE, medianHeight * EDGE_TOLERANCE_FACTOR);
            double colTolerance = Math.max(MIN_EDGE_TOLERANCE, medianWidth * EDGE_TOLERANCE_FACTOR);

            double[] rowBoundaries = clusterEdges(rowEdges, rowTolerance, minY, maxY);
            double[] colBoundaries = clusterEdges(colEdges, colTolerance, minX, maxX);

            Rect bounds = new Rect(
                (int) Math.floor(minX),
                (int) Math.floor(minY),
                (int) Math.ceil(Math.max(1.0, maxX - minX)),
                (int) Math.ceil(Math.max(1.0, maxY - minY))
            );

            int rowCount = Math.max(0, rowBoundaries.length - 1);
            int colCount = Math.max(0, colBoundaries.length - 1);
            Cell[][] matrix = new Cell[rowCount][colCount];
            List<Cell> cells = new ArrayList<>();
            Set<Cell> seen = Collections.newSetFromMap(new IdentityHashMap<>());

            for (Rect rect : rects) {
                int rowStart = locateInterval(rowBoundaries, rect.y + 1e-3);
                int rowEnd = locateInterval(rowBoundaries, rect.y + rect.height - 1e-3);
                int colStart = locateInterval(colBoundaries, rect.x + 1e-3);
                int colEnd = locateInterval(colBoundaries, rect.x + rect.width - 1e-3);

                Cell cell = new Cell(rect, rowStart, rowEnd, colStart, colEnd);
                cells.add(cell);
                for (int r = rowStart; r <= rowEnd; r++) {
                    for (int c = colStart; c <= colEnd; c++) {
                        if (r >= 0 && r < rowCount && c >= 0 && c < colCount) {
                            matrix[r][c] = cell;
                        }
                    }
                }
            }

            return new TableGrid(rowBoundaries, colBoundaries, bounds, matrix, cells);
        }

        private TableGrid(double[] rowEdges,
                           double[] colEdges,
                           Rect bounds,
                           Cell[][] matrix,
                           List<Cell> cells) {
            this.rowEdges = rowEdges;
            this.colEdges = colEdges;
            this.bounds = bounds;
            this.matrix = matrix;
            this.cells = cells;
            this.rowCount = rowEdges.length > 0 ? rowEdges.length - 1 : 0;
            this.colCount = colEdges.length > 0 ? colEdges.length - 1 : 0;
        }

        boolean isEmpty() {
            return rowCount == 0 || colCount == 0;
        }

        Cell cellAt(int row, ColumnType column) {
            int colIndex = column.ordinal();
            if (row < 0 || row >= rowCount || colIndex < 0 || colIndex >= colCount) {
                return null;
            }
            return matrix[row][colIndex];
        }

        List<Cell> cellsForColumn(ColumnType column) {
            int colIndex = column.ordinal();
            List<Cell> result = new ArrayList<>();
            Set<Cell> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int r = 0; r < rowCount; r++) {
                if (colIndex < 0 || colIndex >= colCount) {
                    continue;
                }
                Cell cell = matrix[r][colIndex];
                if (cell != null && !seen.contains(cell)) {
                    seen.add(cell);
                    result.add(cell);
                }
            }
            result.sort(Comparator.comparingInt(cell -> cell.bounds.y));
            return result;
        }
    }

    private static final class Cell {
        final Rect bounds;
        final int rowStart;
        final int rowEnd;
        final int colStart;
        final int colEnd;
        final List<TextFragment> fragments = new ArrayList<>();
        final List<CheckboxFragment> checkboxes = new ArrayList<>();

        Cell(Rect bounds, int rowStart, int rowEnd, int colStart, int colEnd) {
            this.bounds = bounds;
            this.rowStart = Math.max(0, rowStart);
            this.rowEnd = Math.max(this.rowStart, rowEnd);
            this.colStart = Math.max(0, colStart);
            this.colEnd = Math.max(this.colStart, colEnd);
        }

        void addFragment(TextFragment fragment) {
            fragments.add(fragment);
        }

        void addCheckbox(CheckboxFragment fragment) {
            checkboxes.add(fragment);
        }
    }

    private static final class TextFragment {
        final Rect rect;
        final String text;

        TextFragment(Rect rect, String text) {
            this.rect = rect;
            this.text = text;
        }
    }

    private static final class CheckboxFragment {
        final Rect rect;
        final boolean checked;

        CheckboxFragment(Rect rect, boolean checked) {
            this.rect = rect;
            this.checked = checked;
        }
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 0) {
            return (copy[mid - 1] + copy[mid]) / 2.0;
        }
        return copy[mid];
    }

    private static double[] clusterEdges(List<Double> edges, double tolerance, double min, double max) {
        edges.sort(Double::compareTo);
        List<Double> clusters = new ArrayList<>();
        double current = edges.get(0);
        double sum = edges.get(0);
        int count = 1;
        for (int i = 1; i < edges.size(); i++) {
            double value = edges.get(i);
            if (Math.abs(value - current) <= tolerance) {
                sum += value;
                count++;
                current = sum / count;
            } else {
                clusters.add(current);
                current = value;
                sum = value;
                count = 1;
            }
        }
        clusters.add(current);
        if (clusters.size() == 1) {
            return new double[]{min, max};
        }
        clusters.set(0, min);
        clusters.set(clusters.size() - 1, max);
        double[] result = new double[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            result[i] = clusters.get(i);
        }
        return result;
    }
}
