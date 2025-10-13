package com.contentmanagement.forms.api.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
    private static final int OCR_MARGIN = 2;
    private static final double CHECKBOX_ASPECT_TOL = 0.35;
    private static final double CHECKBOX_MIN_AREA = 36.0;
    private static final double CHECKBOX_INK_RATIO = 0.08;

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
        if (grid.rowCount == 0 || grid.colCount == 0) {
            return Collections.emptyList();
        }

        Mat grayContent = toGray(contentMat);
        assignContent(grid, contentMat, grayContent, contentContours);
        grayContent.release();

        propagateMergedCellText(grid, ColumnType.DOCUMENT);
        propagateMergedCellText(grid, ColumnType.DELIVERY_REQUIREMENT);
        propagateMergedCellText(grid, ColumnType.TAB);

        return assembleRecords(grid);
    }

    public static void printRecordsAsTable(List<RowRecord> records) {
        if (records == null || records.isEmpty()) {
            System.out.println("(no rows)");
            return;
        }
        String[] headers = {"Included", "Tab", "Document", "Form Number", "Delivery Requirement", "Header", "Section"};
        List<String[]> rows = new ArrayList<>();
        rows.add(headers);
        for (RowRecord record : records) {
            rows.add(new String[]{
                record.isIncluded() ? "✔" : "",
                record.getTab(),
                record.getDocument(),
                record.getFormNumber(),
                record.getDeliveryRequirement(),
                record.isHeaderRow() ? "H" : "",
                record.isSectionRow() ? "S" : ""
            });
        }

        int[] widths = new int[headers.length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], measureMaxWidth(row[i]));
            }
        }

        String border = buildBorder(widths);
        System.out.println(border);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            List<String[]> wrapped = wrapRow(row, widths);
            for (int lineIndex = 0; lineIndex < wrapped.size(); lineIndex++) {
                String[] line = wrapped.get(lineIndex);
                StringBuilder out = new StringBuilder("|");
                for (int col = 0; col < line.length; col++) {
                    out.append(" ").append(pad(line[col], widths[col])).append(" |");
                }
                System.out.println(out);
            }
            System.out.println(border);
            if (i == 0) {
                // already printed border after header
            }
        }
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

    private static final class TableGrid {
        final double[] rowEdges;
        final double[] colEdges;
        final int rowCount;
        final int colCount;
        final Rect bounds;
        final Cell[][] cellByRowCol;
        final RowData[] rows;

        static TableGrid fromContours(List<MatOfPoint> cellContours) {
            List<Rect> rects = new ArrayList<>();
            for (MatOfPoint contour : cellContours) {
                Rect rect = Imgproc.boundingRect(contour);
                if (rect.width > 0 && rect.height > 0) {
                    rects.add(rect);
                }
            }
            if (rects.isEmpty()) {
                return new TableGrid(new double[0], new double[0], new Rect(), new Cell[0][0], new RowData[0]);
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

            Rect tableBounds = new Rect(
                (int) Math.floor(minX),
                (int) Math.floor(minY),
                (int) Math.ceil(Math.max(1.0, maxX - minX)),
                (int) Math.ceil(Math.max(1.0, maxY - minY))
            );

            int rowCount = Math.max(0, rowBoundaries.length - 1);
            int colCount = Math.max(0, colBoundaries.length - 1);
            Cell[][] matrix = new Cell[rowCount][colCount];

            for (Rect rect : rects) {
                int rowStart = locateInterval(rowBoundaries, rect.y + 1e-3);
                int rowEnd = locateInterval(rowBoundaries, rect.y + rect.height - 1e-3);
                int colStart = locateInterval(colBoundaries, rect.x + 1e-3);
                int colEnd = locateInterval(colBoundaries, rect.x + rect.width - 1e-3);

                Cell cell = new Cell(rect, rowStart, rowEnd, colStart, colEnd);
                for (int r = rowStart; r <= rowEnd; r++) {
                    for (int c = colStart; c <= colEnd; c++) {
                        if (r >= 0 && r < rowCount && c >= 0 && c < colCount) {
                            matrix[r][c] = cell;
                        }
                    }
                }
            }

            RowData[] rows = new RowData[rowCount];
            for (int r = 0; r < rowCount; r++) {
                rows[r] = new RowData(r, colCount);
            }

            return new TableGrid(rowBoundaries, colBoundaries, tableBounds, matrix, rows);
        }

        private TableGrid(double[] rowEdges,
                           double[] colEdges,
                           Rect bounds,
                           Cell[][] cellByRowCol,
                           RowData[] rows) {
            this.rowEdges = rowEdges;
            this.colEdges = colEdges;
            this.bounds = bounds;
            this.cellByRowCol = cellByRowCol;
            this.rows = rows;
            this.rowCount = rows.length;
            this.colCount = colEdges.length > 0 ? colEdges.length - 1 : 0;
        }

        Cell cellAt(int row, int col) {
            if (row < 0 || col < 0 || row >= rowCount || col >= colCount) {
                return null;
            }
            return cellByRowCol[row][col];
        }
    }

    private static final class Cell {
        final Rect bounds;
        final int rowStart;
        final int rowEnd;
        final int colStart;
        final int colEnd;
        final List<TextFragment> fragments = new ArrayList<>();
        final List<Rect> checkboxRects = new ArrayList<>();

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

        void addCheckbox(Rect rect) {
            checkboxRects.add(rect);
        }
    }

    private static final class RowData {
        final int index;
        final String[] textByColumn;
        boolean included;
        boolean headerRow;
        boolean sectionRow;

        RowData(int index, int columnCount) {
            this.index = index;
            this.textByColumn = new String[columnCount];
        }

        void set(ColumnType column, String value) {
            int idx = column.ordinal();
            if (idx >= 0 && idx < textByColumn.length) {
                textByColumn[idx] = normalize(value);
            }
        }

        String get(ColumnType column) {
            int idx = column.ordinal();
            if (idx < 0 || idx >= textByColumn.length) {
                return "";
            }
            return textByColumn[idx] == null ? "" : textByColumn[idx];
        }
    }

    private static final class TextFragment {
        final Rect rect;
        final String text;

        TextFragment(Rect rect, String text) {
            this.rect = rect;
            this.text = normalize(text);
        }
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
            Cell cell = grid.cellAt(rowIndex, colIndex);
            if (cell == null) {
                continue;
            }

            if (isCheckboxCandidate(rect)) {
                boolean checked = isCheckboxMarked(gray, rect);
                if (checked) {
                    markRowsIncluded(grid, cell, true);
                }
                cell.addCheckbox(rect);
                if (!checked) {
                    markRowsIncluded(grid, cell, false);
                }
                continue;
            }

            String text = performOcr(contentMat, rect);
            if (text.isBlank()) {
                continue;
            }
            TextFragment fragment = new TextFragment(rect, text);
            cell.addFragment(fragment);
        }

        for (int row = 0; row < grid.rowCount; row++) {
            for (ColumnType column : ColumnType.values()) {
                if (column.ordinal() >= grid.colCount) {
                    continue;
                }
                Cell cell = grid.cellAt(row, column.ordinal());
                if (cell == null) {
                    continue;
                }
                String combined = combineText(cell.fragments);
                grid.rows[row].set(column, combined);
            }
        }

        classifyRows(grid);
    }

    private static void classifyRows(TableGrid grid) {
        for (RowData row : grid.rows) {
            String document = row.get(ColumnType.DOCUMENT);
            String tab = row.get(ColumnType.TAB);
            boolean fullSpan = true;
            for (int c = 0; c < grid.colCount; c++) {
                if (grid.cellAt(row.index, c) == null) {
                    fullSpan = false;
                    break;
                }
            }
            boolean uppercaseDoc = !document.isBlank() && document.equals(document.toUpperCase(Locale.ROOT));
            if (row.index == 0 && document.contains("DELIVERY PACKAGE CONTENT")) {
                row.headerRow = true;
            } else if (uppercaseDoc && fullSpan) {
                row.sectionRow = true;
            } else if (document.contains("DOCUMENTS") && tab.contains("TAB")) {
                row.headerRow = true;
            }
        }
    }

    private static void propagateMergedCellText(TableGrid grid, ColumnType column) {
        int columnIndex = column.ordinal();
        if (columnIndex >= grid.colCount) {
            return;
        }
        for (int row = 0; row < grid.rowCount; row++) {
            Cell cell = grid.cellAt(row, columnIndex);
            if (cell == null) {
                continue;
            }
            if (cell.rowStart == cell.rowEnd) {
                continue;
            }
            String text = combineText(cell.fragments);
            if (text.isEmpty()) {
                continue;
            }
            for (int r = cell.rowStart; r <= cell.rowEnd; r++) {
                grid.rows[r].set(column, text);
            }
        }
    }

    private static List<RowRecord> assembleRecords(TableGrid grid) {
        List<RowRecord> records = new ArrayList<>();
        for (RowData row : grid.rows) {
            if (row.headerRow || row.sectionRow) {
                records.add(new RowRecord(false,
                    row.get(ColumnType.TAB),
                    row.get(ColumnType.DOCUMENT),
                    row.get(ColumnType.FORM_NUMBER),
                    row.get(ColumnType.DELIVERY_REQUIREMENT),
                    row.headerRow,
                    row.sectionRow));
                continue;
            }

            boolean hasData = Arrays.stream(ColumnType.values())
                .filter(type -> type != ColumnType.CHECK_INCLUDED)
                .anyMatch(type -> !row.get(type).isEmpty());

            if (!hasData && !row.included) {
                continue;
            }

            records.add(new RowRecord(row.included,
                row.get(ColumnType.TAB),
                row.get(ColumnType.DOCUMENT),
                row.get(ColumnType.FORM_NUMBER),
                row.get(ColumnType.DELIVERY_REQUIREMENT),
                false,
                false));
        }
        return records;
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

    private void markRowsIncluded(TableGrid grid, Cell cell, boolean checked) {
        for (int r = cell.rowStart; r <= cell.rowEnd && r < grid.rowCount; r++) {
            if (checked) {
                grid.rows[r].included = true;
            } else if (!grid.rows[r].included) {
                grid.rows[r].included = false;
            }
        }
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

    private static String combineText(List<TextFragment> fragments) {
        if (fragments.isEmpty()) {
            return "";
        }
        return fragments.stream()
            .sorted(Comparator.comparingInt(fragment -> fragment.rect.y))
            .map(fragment -> fragment.text)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining("\n"));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
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
}
