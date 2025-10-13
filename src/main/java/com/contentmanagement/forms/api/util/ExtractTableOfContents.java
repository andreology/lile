package com.contentmanagement.forms.api.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

public final class ExtractTableOfContents {

    private static final double CELL_CENTER_OFFSET = 0.6;
    private static final double EDGE_TOLERANCE_FACTOR = 0.35;
    private static final double MIN_EDGE_TOLERANCE = 3.0;
    private static final int OCR_MARGIN = 2;
    private static final double CHECKBOX_ASPECT_TOL = 0.35;
    private static final double CHECKBOX_MIN_AREA = 36.0;
    private static final double CHECKBOX_INK_RATIO = 0.08;
    private static final double BAND_MARGIN_FACTOR = 0.15;

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
        ContentModel content = assignContent(rowSlices, cellGrid, grid, contentMat, contentContours);
        ColumnModel columnModel = inferColumns(grid);
        defineCheckboxBands(content.checkboxes, grid);
        return assembleRows(rowSlices, columnModel, grid, cellGrid, content);
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
            this.tab = documentOrEmpty(tab);
            this.document = documentOrEmpty(document);
            this.formNumber = documentOrEmpty(formNumber);
            this.deliveryRequirement = documentOrEmpty(deliveryRequirement);
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
        final boolean[] rowHasFullWidthCell;

        CellGrid(int rowCount,
                 int colCount,
                 Cell[][] cellByRowCol,
                 List<Cell> cells,
                 boolean[] rowHasFullWidthCell) {
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.cellByRowCol = cellByRowCol;
            this.cells = cells;
            this.rowHasFullWidthCell = rowHasFullWidthCell;
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
        final List<CheckboxObservation> checkboxes = new ArrayList<>();

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

        void addCheckbox(CheckboxObservation observation) {
            checkboxes.add(observation);
        }

        boolean hasCheckbox() {
            return !checkboxes.isEmpty();
        }

        List<CheckboxObservation> getCheckboxes() {
            return checkboxes;
        }

        String getText(int column) {
            return combineFragments(fragments.get(column));
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

    private static final class CheckboxObservation {
        final Rect rect;
        final boolean checked;

        CheckboxObservation(Rect rect, boolean checked) {
            this.rect = rect;
            this.checked = checked;
        }

        double centerY() {
            return rect.y + rect.height / 2.0;
        }
    }

    private static final class CheckboxBand {
        final RowSlice slice;
        final CheckboxObservation observation;
        double bandTop;
        double bandBottom;

        CheckboxBand(RowSlice slice, CheckboxObservation observation) {
            this.slice = slice;
            this.observation = observation;
        }

        double centerY() {
            return observation.centerY();
        }

        boolean isChecked() {
            return observation.checked;
        }
    }

    private static final class ContentModel {
        final List<List<TextFragment>> columnFragments;
        final List<CheckboxBand> checkboxes;

        ContentModel(List<List<TextFragment>> columnFragments, List<CheckboxBand> checkboxes) {
            this.columnFragments = columnFragments;
            this.checkboxes = checkboxes;
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

    private static final class DataRow {
        final CheckboxBand band;
        final boolean included;
        String tab;
        String document;
        String formNumber;
        String deliveryRequirement;

        DataRow(CheckboxBand band, boolean included) {
            this.band = band;
            this.included = included;
        }

        RowRecord toRecord() {
            return new RowRecord(included, tab, document, formNumber, deliveryRequirement, false, false);
        }

        String getField(ColumnType type) {
            switch (type) {
                case TAB:
                    return tab;
                case DOCUMENT:
                    return document;
                case FORM_NUMBER:
                    return formNumber;
                case DELIVERY_REQUIREMENT:
                    return deliveryRequirement;
                default:
                    return "";
            }
        }

        void setField(ColumnType type, String value) {
            String normalized = normalizeLines(value);
            switch (type) {
                case TAB:
                    tab = normalized;
                    break;
                case DOCUMENT:
                    document = normalized;
                    break;
                case FORM_NUMBER:
                    formNumber = normalized;
                    break;
                case DELIVERY_REQUIREMENT:
                    deliveryRequirement = normalized;
                    break;
                default:
                    break;
            }
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

        boolean[] rowHasFullWidthCell = new boolean[rowCount];
        for (Cell cell : cells) {
            if (cell.colStart == 0 && cell.colEnd == colCount - 1) {
                for (int r = cell.rowStart; r <= cell.rowEnd && r < rowCount; r++) {
                    rowHasFullWidthCell[r] = true;
                }
            }
        }

        return new CellGrid(rowCount, colCount, matrix, cells, rowHasFullWidthCell);
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

    private ContentModel assignContent(List<RowSlice> rows,
                                       CellGrid cellGrid,
                                       GridModel grid,
                                       Mat contentMat,
                                       List<MatOfPoint> contentContours) {
        List<List<TextFragment>> columnFragments = new ArrayList<>(grid.colCount);
        for (int i = 0; i < grid.colCount; i++) {
            columnFragments.add(new ArrayList<>());
        }
        List<CheckboxBand> checkboxBands = new ArrayList<>();

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
                CheckboxObservation observation = new CheckboxObservation(rect, checked);
                slice.addCheckbox(observation);
                checkboxBands.add(new CheckboxBand(slice, observation));
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
            columnFragments.get(colIndex).add(fragment);
        }
        gray.release();

        return new ContentModel(columnFragments, checkboxBands);
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

    private static ColumnModel inferColumns(GridModel grid) {
        int colCount = grid.colCount;
        ColumnType[] mapping = new ColumnType[colCount];
        ColumnType[] expectedOrder = {
            ColumnType.CHECK_INCLUDED,
            ColumnType.TAB,
            ColumnType.DOCUMENT,
            ColumnType.FORM_NUMBER,
            ColumnType.DELIVERY_REQUIREMENT
        };
        for (int i = 0; i < colCount; i++) {
            mapping[i] = i < expectedOrder.length ? expectedOrder[i] : ColumnType.UNKNOWN;
        }
        Map<ColumnType, Integer> indexByType = new HashMap<>();
        for (int i = 0; i < mapping.length; i++) {
            indexByType.putIfAbsent(mapping[i], i);
        }
        return new ColumnModel(mapping, indexByType);
    }

    private static void defineCheckboxBands(List<CheckboxBand> checkboxes, GridModel grid) {
        if (checkboxes.isEmpty()) {
            return;
        }
        checkboxes.sort(Comparator.comparingDouble(CheckboxBand::centerY));
        double margin = Math.max(3.0, grid.medianRowHeight * BAND_MARGIN_FACTOR);
        for (int i = 0; i < checkboxes.size(); i++) {
            CheckboxBand current = checkboxes.get(i);
            double center = current.centerY();
            double top = current.slice.top - margin;
            double bottom = current.slice.bottom + margin;

            if (i > 0) {
                double midpoint = (checkboxes.get(i - 1).centerY() + center) / 2.0;
                top = Math.max(top, midpoint);
            }
            if (i + 1 < checkboxes.size()) {
                double midpoint = (center + checkboxes.get(i + 1).centerY()) / 2.0;
                bottom = Math.min(bottom, midpoint);
            }

            if (bottom <= top) {
                bottom = top + Math.max(2.0, grid.medianRowHeight * 0.4);
            }

            top = Math.max(top, grid.tableBounds.y);
            bottom = Math.min(bottom, grid.tableBounds.y + grid.tableBounds.height);

            current.bandTop = top;
            current.bandBottom = bottom;
        }
    }

    private static List<RowRecord> assembleRows(List<RowSlice> slices,
                                                ColumnModel columns,
                                                GridModel grid,
                                                CellGrid cellGrid,
                                                ContentModel content) {
        List<RowRecord> records = new ArrayList<>();
        int tabIdx = safeIndex(columns.indexOf(ColumnType.TAB));
        int docIdx = safeIndex(columns.indexOf(ColumnType.DOCUMENT));
        int formIdx = safeIndex(columns.indexOf(ColumnType.FORM_NUMBER));
        int deliveryIdx = safeIndex(columns.indexOf(ColumnType.DELIVERY_REQUIREMENT));

        boolean[] headerFlags = identifyHeaderRows(slices, tabIdx, docIdx, grid);
        boolean[] sectionFlags = identifySectionRows(slices, cellGrid, docIdx, headerFlags);

        Map<Integer, List<DataRow>> dataRowsBySlice = new HashMap<>();
        List<DataRow> dataRows = new ArrayList<>();

        if (!content.checkboxes.isEmpty()) {
            for (CheckboxBand band : content.checkboxes) {
                if (headerFlags[band.slice.index]) {
                    continue;
                }
                DataRow dataRow = buildDataRow(band, content, cellGrid, tabIdx, docIdx, formIdx, deliveryIdx);
                dataRows.add(dataRow);
                dataRowsBySlice.computeIfAbsent(band.slice.index, key -> new ArrayList<>()).add(dataRow);
            }
            propagateDataRows(dataRows, columns, cellGrid, tabIdx, docIdx, deliveryIdx);
        }

        for (int i = 0; i < slices.size(); i++) {
            RowSlice slice = slices.get(i);
            if (headerFlags[i]) {
                records.add(new RowRecord(false,
                    slice.getText(tabIdx),
                    slice.getText(docIdx),
                    slice.getText(formIdx),
                    slice.getText(deliveryIdx),
                    true,
                    false));
            } else if (sectionFlags[i]) {
                records.add(new RowRecord(false,
                    slice.getText(tabIdx),
                    slice.getText(docIdx),
                    slice.getText(formIdx),
                    slice.getText(deliveryIdx),
                    false,
                    true));
            }

            List<DataRow> rowsForSlice = dataRowsBySlice.get(i);
            if (rowsForSlice != null) {
                rowsForSlice.sort(Comparator.comparingDouble(row -> row.band.centerY()));
                for (DataRow dataRow : rowsForSlice) {
                    records.add(dataRow.toRecord());
                }
            } else if (!headerFlags[i] && !sectionFlags[i]) {
                String tab = slice.getText(tabIdx);
                String document = slice.getText(docIdx);
                String form = slice.getText(formIdx);
                String delivery = slice.getText(deliveryIdx);
                if (!tab.isEmpty() || !document.isEmpty() || !form.isEmpty() || !delivery.isEmpty()) {
                    records.add(new RowRecord(false, tab, document, form, delivery, false, false));
                }
            }
        }

        return records;
    }

    private static DataRow buildDataRow(CheckboxBand band,
                                        ContentModel content,
                                        CellGrid cellGrid,
                                        int tabIdx,
                                        int docIdx,
                                        int formIdx,
                                        int deliveryIdx) {
        DataRow dataRow = new DataRow(band, band.isChecked());
        double top = band.bandTop;
        double bottom = band.bandBottom;

        dataRow.tab = collectBandText(content.columnFragments, tabIdx, top, bottom);
        dataRow.document = collectBandText(content.columnFragments, docIdx, top, bottom);
        dataRow.formNumber = collectBandText(content.columnFragments, formIdx, top, bottom);
        dataRow.deliveryRequirement = collectBandText(content.columnFragments, deliveryIdx, top, bottom);

        if ((dataRow.tab == null || dataRow.tab.isBlank()) && tabIdx >= 0) {
            dataRow.tab = fallbackFromCell(cellGrid, band.slice.index, tabIdx);
        }
        if ((dataRow.document == null || dataRow.document.isBlank()) && docIdx >= 0) {
            dataRow.document = fallbackFromCell(cellGrid, band.slice.index, docIdx);
        }
        if ((dataRow.formNumber == null || dataRow.formNumber.isBlank()) && formIdx >= 0) {
            dataRow.formNumber = fallbackFromCell(cellGrid, band.slice.index, formIdx);
        }
        if ((dataRow.deliveryRequirement == null || dataRow.deliveryRequirement.isBlank()) && deliveryIdx >= 0) {
            dataRow.deliveryRequirement = fallbackFromCell(cellGrid, band.slice.index, deliveryIdx);
        }

        dataRow.tab = normalizeLines(dataRow.tab);
        dataRow.document = normalizeLines(dataRow.document);
        dataRow.formNumber = normalizeLines(dataRow.formNumber);
        dataRow.deliveryRequirement = normalizeLines(dataRow.deliveryRequirement);

        return dataRow;
    }

    private static void propagateDataRows(List<DataRow> rows,
                                          ColumnModel columns,
                                          CellGrid cellGrid,
                                          int tabIdx,
                                          int docIdx,
                                          int deliveryIdx) {
        propagateColumn(rows, ColumnType.TAB, tabIdx, cellGrid);
        propagateColumn(rows, ColumnType.DOCUMENT, docIdx, cellGrid);
        propagateColumn(rows, ColumnType.DELIVERY_REQUIREMENT, deliveryIdx, cellGrid);
    }

    private static void propagateColumn(List<DataRow> rows,
                                        ColumnType type,
                                        int colIdx,
                                        CellGrid cellGrid) {
        if (colIdx < 0) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            DataRow row = rows.get(i);
            String value = row.getField(type);
            if (value != null && !value.isBlank()) {
                continue;
            }

            CheckboxBand band = row.band;
            Cell cell = cellGrid.cellByRowCol[band.slice.index][colIdx];
            if (cell == null) {
                continue;
            }

            if (cell.rowStart < cell.rowEnd) {
                for (int j = i - 1; j >= 0; j--) {
                    DataRow candidate = rows.get(j);
                    if (candidate.band.slice.index >= cell.rowStart && candidate.band.slice.index <= cell.rowEnd) {
                        String candidateValue = candidate.getField(type);
                        if (candidateValue != null && !candidateValue.isBlank()) {
                            row.setField(type, candidateValue);
                            break;
                        }
                    }
                }
            }

            if (row.getField(type) == null || row.getField(type).isBlank()) {
                String cellText = cell.getText(colIdx);
                if (cellText != null && !cellText.isBlank()) {
                    row.setField(type, cellText);
                }
            }
        }
    }

    private static boolean[] identifyHeaderRows(List<RowSlice> slices,
                                                int tabIdx,
                                                int docIdx,
                                                GridModel grid) {
        boolean[] header = new boolean[slices.size()];
        for (int i = 0; i < slices.size(); i++) {
            RowSlice slice = slices.get(i);
            String doc = slice.getText(docIdx).toUpperCase(Locale.ROOT);
            String tab = slice.getText(tabIdx).toUpperCase(Locale.ROOT);
            if ((doc.contains("DELIVERY PACKAGE CONTENT") || (doc.contains("DOCUMENT") && tab.contains("TAB"))) &&
                (slice.top - grid.tableBounds.y) < grid.medianRowHeight * 4) {
                header[i] = true;
            }
        }
        return header;
    }

    private static boolean[] identifySectionRows(List<RowSlice> slices,
                                                 CellGrid cellGrid,
                                                 int docIdx,
                                                 boolean[] headerFlags) {
        boolean[] section = new boolean[slices.size()];
        for (int i = 0; i < slices.size(); i++) {
            RowSlice slice = slices.get(i);
            if (headerFlags[i] || slice.hasCheckbox()) {
                continue;
            }
            String doc = slice.getText(docIdx);
            if (doc.isBlank()) {
                continue;
            }
            boolean looksUpper = doc.equals(doc.toUpperCase(Locale.ROOT)) && doc.length() > 6;
            if ((cellGrid.rowHasFullWidthCell != null && cellGrid.rowHasFullWidthCell[i]) || looksUpper) {
                section[i] = true;
            }
        }
        return section;
    }

    private static String collectBandText(List<List<TextFragment>> columnFragments,
                                          int columnIndex,
                                          double bandTop,
                                          double bandBottom) {
        if (columnIndex < 0 || columnIndex >= columnFragments.size()) {
            return "";
        }
        List<TextFragment> fragments = columnFragments.get(columnIndex);
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        List<TextFragment> selected = new ArrayList<>();
        for (TextFragment fragment : fragments) {
            double center = fragment.rect.y + fragment.rect.height / 2.0;
            if (center >= bandTop && center <= bandBottom) {
                selected.add(fragment);
            } else if (fragment.rect.y >= bandTop && fragment.rect.y + fragment.rect.height <= bandBottom) {
                selected.add(fragment);
            }
        }
        return combineFragments(selected);
    }

    private static String fallbackFromCell(CellGrid cellGrid, int rowIndex, int columnIndex) {
        if (rowIndex < 0 || columnIndex < 0 || rowIndex >= cellGrid.rowCount || columnIndex >= cellGrid.colCount) {
            return "";
        }
        Cell cell = cellGrid.cellByRowCol[rowIndex][columnIndex];
        if (cell == null) {
            return "";
        }
        return cell.getText(columnIndex);
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
