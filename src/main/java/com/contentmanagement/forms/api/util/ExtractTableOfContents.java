package com.contentmanagement.forms.api.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class ExtractTableOfContents {

    private static final double CELL_CENTER_OFFSET = 0.6;
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

        List<Rect> cellRects = extractCellRects(cellContours);
        if (cellRects.isEmpty()) {
            return Collections.emptyList();
        }

        GridModel grid = buildGridModel(cellRects);
        CellGrid cellGrid = buildCellGrid(grid, cellRects);
        List<RowSlice> rowSlices = createRowSlices(grid);
        assignContent(rowSlices, cellGrid, grid, contentMat, contentContours);
        ColumnModel columnModel = inferColumns(rowSlices, grid.colCount);
        propagateText(rowSlices, columnModel, cellGrid);
        return assembleRows(rowSlices, columnModel);
    }

    public static final class RowRecord {
        private final boolean included;
        private final String tab;
        private final String document;
        private final String formNumber;
        private final String deliveryRequirement;

        RowRecord(boolean included, String tab, String document, String formNumber, String deliveryRequirement) {
            this.included = included;
            this.tab = documentOrEmpty(tab);
            this.document = documentOrEmpty(document);
            this.formNumber = documentOrEmpty(formNumber);
            this.deliveryRequirement = documentOrEmpty(deliveryRequirement);
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

        @Override
        public String toString() {
            return "RowRecord{" +
                "included=" + included +
                ", tab='" + tab + '\'' +
                ", document='" + document + '\'' +
                ", formNumber='" + formNumber + '\'' +
                ", deliveryRequirement='" + deliveryRequirement + '\'' +
                '}';
        }

        private static String documentOrEmpty(String text) {
            return text == null ? "" : text;
        }
    }

    private static final class GridModel {
        final double[] rowBoundaries;
        final double[] colBoundaries;
        final Rect tableBounds;
        final double rowTolerance;
        final double colTolerance;
        final double medianRowHeight;
        final int rowCount;
        final int colCount;

        GridModel(double[] rowBoundaries,
                  double[] colBoundaries,
                  Rect tableBounds,
                  double rowTolerance,
                  double colTolerance,
                  double medianRowHeight) {
            this.rowBoundaries = rowBoundaries;
            this.colBoundaries = colBoundaries;
            this.tableBounds = tableBounds;
            this.rowTolerance = rowTolerance;
            this.colTolerance = colTolerance;
            this.medianRowHeight = medianRowHeight;
            this.rowCount = Math.max(0, rowBoundaries.length - 1);
            this.colCount = Math.max(0, colBoundaries.length - 1);
        }
    }

    private static final class CellGrid {
        final int rowCount;
        final int colCount;
        final Cell[][] cellByRowCol;
        final List<Cell> cells;

        CellGrid(int rowCount, int colCount, Cell[][] cellByRowCol, List<Cell> cells) {
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.cellByRowCol = cellByRowCol;
            this.cells = cells;
        }
    }

    private static final class Cell {
        int rowStart;
        int rowEnd;
        int colStart;
        int colEnd;
        Rect bounds;
        final Map<Integer, List<TextFragment>> fragments = new HashMap<>();

        void addFragment(int column, TextFragment fragment) {
            if (column < 0) {
                return;
            }
            fragments.computeIfAbsent(column, key -> new ArrayList<>()).add(fragment);
        }

        String getText(int column) {
            return combineFragments(fragments.get(column));
        }
    }

    private static final class RowSlice {
        final int index;
        final int top;
        final int bottom;
        final Map<Integer, List<TextFragment>> fragments = new HashMap<>();
        final Map<Integer, String> overrides = new HashMap<>();
        Boolean checkbox;

        RowSlice(int index, int top, int bottom) {
            this.index = index;
            this.top = top;
            this.bottom = bottom;
        }

        void addFragment(int column, TextFragment fragment) {
            if (column < 0) {
                return;
            }
            fragments.computeIfAbsent(column, key -> new ArrayList<>()).add(fragment);
        }

        void setOverride(int column, String text) {
            String normalized = normalizeLines(text);
            if (!normalized.isBlank()) {
                overrides.put(column, normalized);
            }
        }

        String getRawText(int column) {
            return combineFragments(fragments.get(column));
        }

        String getResolvedText(int column) {
            if (column < 0) {
                return "";
            }
            String override = overrides.get(column);
            return override != null ? override : getRawText(column);
        }

        void acceptCheckbox(boolean checked) {
            if (checkbox == null || checked) {
                checkbox = checked;
            }
        }

        boolean hasCheckbox() {
            return checkbox != null;
        }

        boolean checkboxValue() {
            return Boolean.TRUE.equals(checkbox);
        }
    }

    private static final class TextFragment {
        final Rect rect;
        final String text;

        TextFragment(Rect rect, String text) {
            this.rect = rect;
            this.text = text == null ? "" : text;
        }
    }

    private static final class ColumnModel {
        final ColumnType[] mapping;
        final Map<ColumnType, Integer> indexByType;

        ColumnModel(ColumnType[] mapping, Map<ColumnType, Integer> indexByType) {
            this.mapping = mapping;
            this.indexByType = indexByType;
        }

        Integer indexOf(ColumnType type) {
            return indexByType.get(type);
        }
    }

    private enum ColumnType {
        CHECK_INCLUDED(false),
        TAB(true),
        DOCUMENT(true),
        FORM_NUMBER(false),
        DELIVERY_REQUIREMENT(true),
        UNKNOWN(false);

        private final boolean propagate;

        ColumnType(boolean propagate) {
            this.propagate = propagate;
        }

        boolean shouldPropagate() {
            return propagate;
        }
    }

    private static List<Rect> extractCellRects(List<MatOfPoint> contours) {
        List<Rect> rects = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.width > 0 && rect.height > 0) {
                rects.add(rect);
            }
        }
        return rects;
    }

    private static GridModel buildGridModel(List<Rect> cellRects) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;

        double[] heights = new double[cellRects.size()];
        double[] widths = new double[cellRects.size()];
        List<Double> rowEdges = new ArrayList<>();
        List<Double> colEdges = new ArrayList<>();

        for (int i = 0; i < cellRects.size(); i++) {
            Rect rect = cellRects.get(i);
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

        if (minX == Double.MAX_VALUE) {
            minX = minY = 0;
            maxX = maxY = 0;
        }

        double medianHeight = median(heights);
        double medianWidth = median(widths);
        double rowTolerance = Math.max(MIN_EDGE_TOLERANCE, medianHeight * EDGE_TOLERANCE_FACTOR);
        double colTolerance = Math.max(MIN_EDGE_TOLERANCE, medianWidth * EDGE_TOLERANCE_FACTOR);

        double[] rowBoundaries = clusterEdges(rowEdges, rowTolerance, minY, maxY);
        double[] colBoundaries = clusterEdges(colEdges, colTolerance, minX, maxX);

        if (rowBoundaries.length < 2) {
            rowBoundaries = new double[]{minY, maxY > minY ? maxY : minY + Math.max(1.0, medianHeight)};
        }
        if (colBoundaries.length < 2) {
            colBoundaries = new double[]{minX, maxX > minX ? maxX : minX + Math.max(1.0, medianWidth)};
        }

        double medianRowHeight = medianDifferences(rowBoundaries);

        Rect bounds = new Rect(
            (int) Math.floor(minX),
            (int) Math.floor(minY),
            (int) Math.ceil(Math.max(1.0, maxX - minX)),
            (int) Math.ceil(Math.max(1.0, maxY - minY))
        );

        return new GridModel(rowBoundaries, colBoundaries, bounds, rowTolerance, colTolerance, medianRowHeight);
    }

    private static double[] clusterEdges(List<Double> values, double tolerance, double min, double max) {
        if (values.isEmpty()) {
            return new double[0];
        }
        values.sort(Double::compareTo);
        List<Double> clusters = new ArrayList<>();
        double clusterCenter = values.get(0);
        double sum = values.get(0);
        int count = 1;

        for (int i = 1; i < values.size(); i++) {
            double value = values.get(i);
            if (Math.abs(value - clusterCenter) <= tolerance) {
                sum += value;
                count++;
                clusterCenter = sum / count;
            } else {
                clusters.add(clusterCenter);
                clusterCenter = value;
                sum = value;
                count = 1;
            }
        }
        clusters.add(clusterCenter);

        if (clusters.size() == 1) {
            double[] fallback = new double[2];
            fallback[0] = min;
            fallback[1] = max > min ? max : min + 1.0;
            return fallback;
        }

        clusters.set(0, min);
        clusters.set(clusters.size() - 1, max);

        double[] result = new double[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            result[i] = clusters.get(i);
        }
        return result;
    }

    private static double median(double[] values) {
        if (values == null || values.length == 0) {
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

    private static double medianDifferences(double[] boundaries) {
        if (boundaries.length < 2) {
            return 0;
        }
        double[] diffs = new double[boundaries.length - 1];
        for (int i = 0; i < diffs.length; i++) {
            diffs[i] = Math.max(1.0, boundaries[i + 1] - boundaries[i]);
        }
        return median(diffs);
    }

    private static CellGrid buildCellGrid(GridModel grid, List<Rect> cellRects) {
        int rowCount = grid.rowCount;
        int colCount = grid.colCount;
        Cell[][] matrix = new Cell[rowCount][colCount];
        List<Cell> cells = new ArrayList<>();

        for (Rect rect : cellRects) {
            Cell cell = new Cell();
            cell.bounds = new Rect(rect.x, rect.y, rect.width, rect.height);

            cell.rowStart = clampIntervalIndex(locateInterval(grid.rowBoundaries, rect.y + CELL_CENTER_OFFSET, grid.rowTolerance), rowCount);
            cell.rowEnd = clampIntervalIndex(locateInterval(grid.rowBoundaries, rect.y + rect.height - CELL_CENTER_OFFSET, grid.rowTolerance), rowCount);
            if (cell.rowEnd < cell.rowStart) {
                cell.rowEnd = cell.rowStart;
            }

            cell.colStart = clampIntervalIndex(locateInterval(grid.colBoundaries, rect.x + CELL_CENTER_OFFSET, grid.colTolerance), colCount);
            cell.colEnd = clampIntervalIndex(locateInterval(grid.colBoundaries, rect.x + rect.width - CELL_CENTER_OFFSET, grid.colTolerance), colCount);
            if (cell.colEnd < cell.colStart) {
                cell.colEnd = cell.colStart;
            }

            cells.add(cell);

            for (int r = cell.rowStart; r <= cell.rowEnd && r < rowCount; r++) {
                for (int c = cell.colStart; c <= cell.colEnd && c < colCount; c++) {
                    Cell existing = matrix[r][c];
                    if (existing == null || area(cell.bounds) < area(existing.bounds)) {
                        matrix[r][c] = cell;
                    }
                }
            }
        }

        return new CellGrid(rowCount, colCount, matrix, cells);
    }

    private static int locateInterval(double[] boundaries, double value, double tolerance) {
        if (boundaries.length < 2) {
            return 0;
        }
        if (value < boundaries[0] - tolerance) {
            return 0;
        }
        if (value > boundaries[boundaries.length - 1] + tolerance) {
            return boundaries.length - 2;
        }
        for (int i = 0; i < boundaries.length - 1; i++) {
            double start = boundaries[i] - tolerance;
            double end = boundaries[i + 1] + tolerance;
            if (value >= start && value <= end) {
                return i;
            }
        }
        return Math.max(0, Math.min(boundaries.length - 2, (int) ((value - boundaries[0]) / Math.max(1.0, boundaries[1] - boundaries[0]))));
    }

    private static int clampIntervalIndex(int index, int count) {
        if (count <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        if (index >= count) {
            return count - 1;
        }
        return index;
    }

    private static double area(Rect rect) {
        return Math.max(1, rect.width) * Math.max(1, rect.height);
    }

    private static List<RowSlice> createRowSlices(GridModel grid) {
        List<RowSlice> slices = new ArrayList<>();
        for (int i = 0; i < grid.rowCount; i++) {
            int top = (int) Math.floor(grid.rowBoundaries[i]);
            int bottom = (int) Math.ceil(grid.rowBoundaries[i + 1]);
            slices.add(new RowSlice(i, top, bottom));
        }
        return slices;
    }

    private void assignContent(List<RowSlice> rows,
                               CellGrid cellGrid,
                               GridModel grid,
                               Mat contentMat,
                               List<MatOfPoint> contentContours) {
        Mat gray = toGray(contentMat);
        for (MatOfPoint contour : contentContours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (!intersects(rect, grid.tableBounds, 3)) {
                continue;
            }

            double centerX = rect.x + rect.width / 2.0;
            double centerY = rect.y + rect.height / 2.0;
            int rowIndex = locateInterval(grid.rowBoundaries, centerY, grid.rowTolerance);
            int colIndex = locateInterval(grid.colBoundaries, centerX, grid.colTolerance);

            if (rowIndex < 0 || rowIndex >= cellGrid.rowCount || colIndex < 0 || colIndex >= cellGrid.colCount) {
                continue;
            }

            RowSlice slice = rows.get(rowIndex);
            Cell cell = cellGrid.cellByRowCol[rowIndex][colIndex];
            if (cell == null) {
                cell = findCell(cellGrid.cells, rect, centerX, centerY);
            }

            if (isCheckboxCandidate(rect, grid.medianRowHeight)) {
                boolean checked = isCheckboxMarked(gray, rect);
                slice.acceptCheckbox(checked);
                continue;
            }

            String ocr = performOcr(contentMat, rect);
            if (ocr.isBlank()) {
                continue;
            }

            TextFragment fragment = new TextFragment(rect, ocr);
            slice.addFragment(colIndex, fragment);
            if (cell != null) {
                cell.addFragment(colIndex, fragment);
            }
        }
        gray.release();
    }

    private static Cell findCell(List<Cell> cells, Rect rect, double centerX, double centerY) {
        for (Cell cell : cells) {
            if (contains(cell.bounds, centerX, centerY)) {
                return cell;
            }
        }
        Cell best = null;
        double bestOverlap = 0;
        for (Cell cell : cells) {
            double overlap = intersectionArea(cell.bounds, rect);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = cell;
            }
        }
        return best;
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

    private static boolean contains(Rect rect, double x, double y) {
        return x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height;
    }

    private static double intersectionArea(Rect a, Rect b) {
        int left = Math.max(a.x, b.x);
        int right = Math.min(a.x + a.width, b.x + b.width);
        int top = Math.max(a.y, b.y);
        int bottom = Math.min(a.y + a.height, b.y + b.height);
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return 0;
        }
        return width * (double) height;
    }

    private static boolean intersects(Rect rect, Rect bounds, int margin) {
        return rect.x + rect.width > bounds.x - margin &&
            rect.x < bounds.x + bounds.width + margin &&
            rect.y + rect.height > bounds.y - margin &&
            rect.y < bounds.y + bounds.height + margin;
    }

    private static boolean isCheckboxCandidate(Rect rect, double medianRowHeight) {
        if (rect.width <= 0 || rect.height <= 0) {
            return false;
        }
        double area = rect.width * rect.height;
        if (area < CHECKBOX_MIN_AREA) {
            return false;
        }
        double size = Math.max(rect.width, rect.height);
        if (medianRowHeight > 0) {
            double minSize = Math.max(8.0, medianRowHeight * 0.22);
            double maxSize = Math.max(10.0, medianRowHeight * 0.75);
            if (size < minSize || size > maxSize) {
                return false;
            }
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

    private String performOcr(Mat source, Rect rect) {
        Rect roi = clampRect(rect, source, OCR_MARGIN);
        Mat patch = new Mat(source, roi).clone();
        BufferedImage image = matToBufferedImage(patch);
        patch.release();
        try {
            String raw = OCREngine.doOCR(image);
            return normalizeLines(raw);
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

    private static ColumnModel inferColumns(List<RowSlice> rows, int colCount) {
        ColumnType[] mapping = new ColumnType[colCount];
        List<ColumnType> priority = Arrays.asList(
            ColumnType.CHECK_INCLUDED,
            ColumnType.TAB,
            ColumnType.DOCUMENT,
            ColumnType.FORM_NUMBER,
            ColumnType.DELIVERY_REQUIREMENT
        );

        List<String> headerSamples = collectHeaderSamples(rows, colCount);
        for (ColumnType type : priority) {
            int bestColumn = -1;
            int bestScore = 0;
            for (int col = 0; col < colCount; col++) {
                if (mapping[col] != null) {
                    continue;
                }
                int score = scoreColumn(type, headerSamples.get(col));
                if (score > bestScore) {
                    bestScore = score;
                    bestColumn = col;
                }
            }
            if (bestColumn >= 0 && bestScore > 0) {
                mapping[bestColumn] = type;
            }
        }

        int priorityIndex = 0;
        for (int col = 0; col < colCount; col++) {
            if (mapping[col] != null) {
                continue;
            }
            while (priorityIndex < priority.size() && contains(mapping, priority.get(priorityIndex))) {
                priorityIndex++;
            }
            mapping[col] = priorityIndex < priority.size() ? priority.get(priorityIndex++) : ColumnType.UNKNOWN;
        }

        Map<ColumnType, Integer> indexByType = new HashMap<>();
        for (int i = 0; i < mapping.length; i++) {
            indexByType.putIfAbsent(mapping[i], i);
        }

        return new ColumnModel(mapping, indexByType);
    }

    private static List<String> collectHeaderSamples(List<RowSlice> rows, int colCount) {
        List<String> samples = new ArrayList<>();
        for (int col = 0; col < colCount; col++) {
            StringBuilder builder = new StringBuilder();
            int limit = Math.min(2, rows.size());
            for (int row = 0; row < limit; row++) {
                String text = rows.get(row).getRawText(col);
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append(' ');
                    }
                    builder.append(text);
                }
            }
            samples.add(builder.toString());
        }
        return samples;
    }

    private static int scoreColumn(ColumnType type, String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        int score = 0;
        switch (type) {
            case CHECK_INCLUDED:
                if (upper.contains("CHECK")) {
                    score += 5;
                }
                if (upper.contains("INCLUDE")) {
                    score += 3;
                }
                break;
            case TAB:
                if (upper.contains("TAB")) {
                    score += 6;
                }
                if (upper.contains("INDEX")) {
                    score += 1;
                }
                break;
            case DOCUMENT:
                if (upper.contains("DOCUMENT")) {
                    score += 6;
                } else if (upper.contains("DOC")) {
                    score += 3;
                }
                break;
            case FORM_NUMBER:
                if (upper.contains("FORM")) {
                    score += 5;
                }
                if (upper.contains("NUMBER")) {
                    score += 4;
                }
                if (upper.contains("NO.")) {
                    score += 2;
                }
                break;
            case DELIVERY_REQUIREMENT:
                if (upper.contains("DELIVERY")) {
                    score += 6;
                }
                if (upper.contains("REQUIRE")) {
                    score += 4;
                }
                if (upper.contains("COPY")) {
                    score += 1;
                }
                break;
            default:
                break;
        }
        return score;
    }

    private static boolean contains(ColumnType[] mapping, ColumnType type) {
        for (ColumnType columnType : mapping) {
            if (columnType == type) {
                return true;
            }
        }
        return false;
    }

    private static void propagateText(List<RowSlice> rows, ColumnModel columns, CellGrid grid) {
        for (ColumnType type : EnumSet.of(ColumnType.TAB, ColumnType.DOCUMENT, ColumnType.DELIVERY_REQUIREMENT)) {
            Integer columnIndex = columns.indexOf(type);
            if (columnIndex == null) {
                continue;
            }
            for (int r = 0; r < rows.size(); r++) {
                RowSlice slice = rows.get(r);
                String text = slice.getResolvedText(columnIndex);
                if (!text.isBlank()) {
                    continue;
                }
                Cell cell = grid.cellByRowCol[r][columnIndex];
                if (cell == null) {
                    continue;
                }
                if (cell.rowStart < r) {
                    String donor = "";
                    for (int donorRow = cell.rowStart; donorRow <= cell.rowEnd; donorRow++) {
                        donor = rows.get(donorRow).getResolvedText(columnIndex);
                        if (!donor.isBlank()) {
                            break;
                        }
                    }
                    if (donor.isBlank()) {
                        donor = cell.getText(columnIndex);
                    }
                    if (!donor.isBlank()) {
                        slice.setOverride(columnIndex, donor);
                    }
                }
            }
        }
    }

    private static List<RowRecord> assembleRows(List<RowSlice> rows, ColumnModel columns) {
        int start = findFirstDataRow(rows, columns);
        if (start >= rows.size()) {
            return Collections.emptyList();
        }

        Integer tabIdx = columns.indexOf(ColumnType.TAB);
        Integer docIdx = columns.indexOf(ColumnType.DOCUMENT);
        Integer formIdx = columns.indexOf(ColumnType.FORM_NUMBER);
        Integer delIdx = columns.indexOf(ColumnType.DELIVERY_REQUIREMENT);

        List<RowRecord> records = new ArrayList<>();
        for (int i = start; i < rows.size(); i++) {
            RowSlice row = rows.get(i);
            String tab = normalizeLines(row.getResolvedText(safeIndex(tabIdx)));
            String document = normalizeLines(row.getResolvedText(safeIndex(docIdx)));
            String formNumber = normalizeLines(row.getResolvedText(safeIndex(formIdx)));
            String delivery = normalizeLines(row.getResolvedText(safeIndex(delIdx)));
            boolean included = row.hasCheckbox() && row.checkboxValue();

            if (tab.isEmpty() && document.isEmpty() && formNumber.isEmpty() && delivery.isEmpty() && !row.hasCheckbox()) {
                continue;
            }

            records.add(new RowRecord(included, tab, document, formNumber, delivery));
        }
        return records;
    }

    private static int findFirstDataRow(List<RowSlice> rows, ColumnModel columns) {
        Integer docIdx = columns.indexOf(ColumnType.DOCUMENT);
        Integer formIdx = columns.indexOf(ColumnType.FORM_NUMBER);
        Integer delIdx = columns.indexOf(ColumnType.DELIVERY_REQUIREMENT);

        int index = 0;
        while (index < rows.size()) {
            RowSlice row = rows.get(index);
            if (row.hasCheckbox()) {
                break;
            }
            String doc = row.getResolvedText(safeIndex(docIdx)).toUpperCase(Locale.ROOT);
            String form = row.getResolvedText(safeIndex(formIdx)).toUpperCase(Locale.ROOT);
            String delivery = row.getResolvedText(safeIndex(delIdx)).toUpperCase(Locale.ROOT);
            boolean header = (doc.contains("DOCUMENT") && form.contains("FORM")) ||
                (doc.contains("CHECK") && delivery.contains("DELIVERY")) ||
                doc.contains("DELIVERY PACKAGE");
            if (!header) {
                break;
            }
            index++;
        }
        return index;
    }

    private static int safeIndex(Integer index) {
        return index == null ? -1 : index;
    }

    private static String combineFragments(List<TextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        List<TextFragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingInt(fragment -> fragment.rect.y));
        StringBuilder builder = new StringBuilder();
        int previousBottom = Integer.MIN_VALUE;
        for (TextFragment fragment : sorted) {
            String text = normalizeLines(fragment.text);
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                if (fragment.rect.y > previousBottom + 4) {
                    builder.append('\n');
                } else {
                    builder.append(' ');
                }
            }
            builder.append(text);
            previousBottom = fragment.rect.y + fragment.rect.height;
        }
        return builder.toString().trim();
    }

    private static String normalizeLines(String raw) {
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
}
