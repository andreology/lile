// package com.example.forms; // <-- adjust to your package structure.

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import net.sourceforge.tess4j.ITessAPI.TessPageSegMode;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/**
 * Advanced table-of-contents extractor that aligns table geometry (from a Mat containing only
 * borders) with content (from the full page Mat) and returns RowRecord entries populated via OCR.
 */
public final class ExtractTableOfContents {

    private static final double LINE_CLUSTER_EPS = 3.5;
    private static final double COORD_EPS = 2.0;
    private static final double COVERAGE_THRESHOLD = 0.5;
    private static final int OCR_MARGIN = 2;
    private static final double CHECKBOX_ASPECT_TOL = 0.35;
    private static final double CHECKBOX_MIN_AREA = 36.0;
    private static final double CHECKBOX_INK_RATIO = 0.08;

    private ExtractTableOfContents() {
    }

    public static List<RowRecord> extract(Mat linesMat,
                                          List<MatOfPoint> lineContours,
                                          Mat contentMat,
                                          List<MatOfPoint> contentContours,
                                          Tesseract tesseract) {
        Objects.requireNonNull(linesMat, "linesMat is required");
        Objects.requireNonNull(lineContours, "lineContours is required");
        Objects.requireNonNull(contentMat, "contentMat is required");
        Objects.requireNonNull(contentContours, "contentContours is required");
        Objects.requireNonNull(tesseract, "tesseract is required");

        configureTesseract(tesseract);

        LineSets lineSets = LineSets.fromContours(lineContours, linesMat.size());
        TableGeometry geometry = new TableGeometry(lineSets);
        CellGrid cellGrid = CellGrid.build(geometry);

        List<RowSlice> rows = RowSlice.create(geometry);
        cellGrid.attachToRowSlices(rows);

        ContentAssigner.assign(rows, cellGrid, geometry, contentMat, contentContours, tesseract);
        ColumnModel columns = ColumnModel.infer(rows, geometry);
        Propagator.propagate(rows, columns, cellGrid);

        return RowAssembler.build(rows, columns);
    }

    private static void configureTesseract(Tesseract tess) {
        tess.setLanguage("eng");
        tess.setPageSegMode(TessPageSegMode.PSM_AUTO);
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

    private static final class LineSegment {
        final double position;
        final double start;
        final double end;
        final double thickness;

        private LineSegment(double position, double start, double end, double thickness) {
            this.position = position;
            this.start = start;
            this.end = end;
            this.thickness = thickness;
        }

        static LineSegment horizontal(Rect rect) {
            double pos = rect.y + rect.height / 2.0;
            return new LineSegment(pos, rect.x, rect.x + rect.width, rect.height);
        }

        static LineSegment vertical(Rect rect) {
            double pos = rect.x + rect.width / 2.0;
            return new LineSegment(pos, rect.y, rect.y + rect.height, rect.width);
        }
    }

    private static final class Range {
        double start;
        double end;

        Range(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class LineCluster {
        final double position;
        final List<Range> ranges;
        final double avgThickness;

        LineCluster(double position, List<Range> ranges, double avgThickness) {
            this.position = position;
            this.ranges = ranges;
            this.avgThickness = avgThickness;
        }

        double coverageRatio(double spanStart, double spanEnd) {
            double span = Math.max(1.0, spanEnd - spanStart);
            double covered = 0;
            for (Range range : ranges) {
                double s = Math.max(range.start, spanStart);
                double e = Math.min(range.end, spanEnd);
                if (e > s) {
                    covered += e - s;
                }
            }
            return covered / span;
        }
    }

    private static final class LineSets {
        final List<LineSegment> horizontals;
        final List<LineSegment> verticals;
        final Rect bounds;

        private LineSets(List<LineSegment> horizontals, List<LineSegment> verticals, Rect bounds) {
            this.horizontals = horizontals;
            this.verticals = verticals;
            this.bounds = bounds;
        }

        static LineSets fromContours(List<MatOfPoint> contours, Size fallbackSize) {
            List<LineSegment> horizontals = new ArrayList<>();
            List<LineSegment> verticals = new ArrayList<>();
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE;
            double maxY = Double.MIN_VALUE;

            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                if (rect.width <= 0 || rect.height <= 0) {
                    continue;
                }
                minX = Math.min(minX, rect.x);
                minY = Math.min(minY, rect.y);
                maxX = Math.max(maxX, rect.x + rect.width);
                maxY = Math.max(maxY, rect.y + rect.height);

                if (rect.width >= rect.height) {
                    double ratio = rect.height == 0 ? Double.MAX_VALUE : rect.width / (double) rect.height;
                    if (ratio >= 3.0) {
                        horizontals.add(LineSegment.horizontal(rect));
                        continue;
                    }
                }
                if (rect.height > rect.width) {
                    double ratio = rect.width == 0 ? Double.MAX_VALUE : rect.height / (double) rect.width;
                    if (ratio >= 3.0) {
                        verticals.add(LineSegment.vertical(rect));
                    }
                }
            }

            if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
                minX = 0;
                minY = 0;
                maxX = fallbackSize.width;
                maxY = fallbackSize.height;
            }

            Rect bounds = new Rect(
                (int) Math.floor(Math.max(0, minX)),
                (int) Math.floor(Math.max(0, minY)),
                (int) Math.ceil(Math.max(1, maxX - minX)),
                (int) Math.ceil(Math.max(1, maxY - minY))
            );
            return new LineSets(horizontals, verticals, bounds);
        }
    }

    private static final class TableGeometry {
        final double[] rowBoundaries;
        final double[] colBoundaries;
        final LineCluster[] rowClusters;
        final LineCluster[] colClusters;
        final Rect bounds;
        final int rowCount;
        final int colCount;
        final double medianRowHeight;

        TableGeometry(LineSets lineSets) {
            this.bounds = lineSets.bounds;
            List<LineCluster> horizontalClusters = cluster(lineSets.horizontals);
            List<LineCluster> verticalClusters = cluster(lineSets.verticals);
            this.rowBoundaries = buildBoundaries(horizontalClusters, bounds.y, bounds.y + bounds.height);
            this.colBoundaries = buildBoundaries(verticalClusters, bounds.x, bounds.x + bounds.width);
            this.rowClusters = mapClusters(rowBoundaries, horizontalClusters);
            this.colClusters = mapClusters(colBoundaries, verticalClusters);
            this.rowCount = Math.max(0, rowBoundaries.length - 1);
            this.colCount = Math.max(0, colBoundaries.length - 1);
            this.medianRowHeight = computeMedianRowHeight();
        }

        boolean hasHorizontal(int boundaryIndex, double startX, double endX) {
            if (boundaryIndex < 0 || boundaryIndex >= rowClusters.length) {
                return false;
            }
            LineCluster cluster = rowClusters[boundaryIndex];
            if (cluster == null) {
                return false;
            }
            return cluster.coverageRatio(startX, endX) >= COVERAGE_THRESHOLD;
        }

        boolean hasVertical(int boundaryIndex, double startY, double endY) {
            if (boundaryIndex < 0 || boundaryIndex >= colClusters.length) {
                return false;
            }
            LineCluster cluster = colClusters[boundaryIndex];
            if (cluster == null) {
                return false;
            }
            return cluster.coverageRatio(startY, endY) >= COVERAGE_THRESHOLD;
        }

        int locateRow(double centerY) {
            for (int i = 0; i < rowBoundaries.length - 1; i++) {
                if (centerY >= rowBoundaries[i] - COORD_EPS && centerY <= rowBoundaries[i + 1] + COORD_EPS) {
                    return i;
                }
            }
            return -1;
        }

        int locateColumn(double centerX) {
            for (int i = 0; i < colBoundaries.length - 1; i++) {
                if (centerX >= colBoundaries[i] - COORD_EPS && centerX <= colBoundaries[i + 1] + COORD_EPS) {
                    return i;
                }
            }
            return -1;
        }

        private double computeMedianRowHeight() {
            if (rowCount == 0) {
                return 0;
            }
            double[] heights = new double[rowCount];
            for (int i = 0; i < rowCount; i++) {
                heights[i] = Math.max(1.0, rowBoundaries[i + 1] - rowBoundaries[i]);
            }
            Arrays.sort(heights);
            int mid = heights.length / 2;
            if (heights.length % 2 == 0) {
                return (heights[mid - 1] + heights[mid]) / 2.0;
            }
            return heights[mid];
        }

        private static List<LineCluster> cluster(List<LineSegment> segments) {
            segments.sort(Comparator.comparingDouble(seg -> seg.position));
            List<LineCluster> clusters = new ArrayList<>();
            int i = 0;
            while (i < segments.size()) {
                LineSegment first = segments.get(i);
                double weightedPos = 0.0;
                double weightSum = 0.0;
                double thicknessSum = 0.0;
                List<Range> ranges = new ArrayList<>();
                double clusterCenter = first.position;
                int j = i;
                while (j < segments.size()) {
                    LineSegment seg = segments.get(j);
                    if (weightSum > 0 && Math.abs(seg.position - clusterCenter) > LINE_CLUSTER_EPS) {
                        break;
                    }
                    ranges.add(new Range(seg.start, seg.end));
                    double weight = Math.max(1.0, seg.thickness);
                    weightedPos += seg.position * weight;
                    weightSum += weight;
                    thicknessSum += seg.thickness;
                    clusterCenter = weightedPos / weightSum;
                    j++;
                }
                double position = weightedPos / Math.max(1.0, weightSum);
                double averageThickness = thicknessSum / Math.max(1, ranges.size());
                clusters.add(new LineCluster(position, mergeRanges(ranges), averageThickness));
                i = j;
            }
            return clusters;
        }

        private static List<Range> mergeRanges(List<Range> ranges) {
            ranges.sort(Comparator.comparingDouble(r -> r.start));
            List<Range> merged = new ArrayList<>();
            for (Range range : ranges) {
                if (merged.isEmpty()) {
                    merged.add(new Range(range.start, range.end));
                } else {
                    Range last = merged.get(merged.size() - 1);
                    if (range.start <= last.end + 1.5) {
                        last.end = Math.max(last.end, range.end);
                    } else {
                        merged.add(new Range(range.start, range.end));
                    }
                }
            }
            return merged;
        }

        private static double[] buildBoundaries(List<LineCluster> clusters, double min, double max) {
            List<Double> values = new ArrayList<>();
            values.add(min);
            for (LineCluster cluster : clusters) {
                if (cluster.position > min + 0.5 && cluster.position < max - 0.5) {
                    values.add(cluster.position);
                }
            }
            values.add(max);
            values.sort(Double::compareTo);
            List<Double> unique = new ArrayList<>();
            Double previous = null;
            for (Double value : values) {
                if (previous == null || Math.abs(value - previous) > 1.0) {
                    unique.add(value);
                    previous = value;
                } else {
                    unique.set(unique.size() - 1, (unique.get(unique.size() - 1) + value) / 2.0);
                    previous = unique.get(unique.size() - 1);
                }
            }
            double[] boundaries = new double[unique.size()];
            for (int i = 0; i < unique.size(); i++) {
                boundaries[i] = unique.get(i);
            }
            return boundaries;
        }

        private static LineCluster[] mapClusters(double[] boundaries, List<LineCluster> clusters) {
            LineCluster[] mapped = new LineCluster[boundaries.length];
            for (LineCluster cluster : clusters) {
                int index = nearestBoundary(boundaries, cluster.position);
                if (index >= 0 && mapped[index] == null) {
                    mapped[index] = cluster;
                }
            }
            return mapped;
        }

        private static int nearestBoundary(double[] boundaries, double position) {
            int result = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < boundaries.length; i++) {
                double delta = Math.abs(boundaries[i] - position);
                if (delta < best && delta <= LINE_CLUSTER_EPS * 2) {
                    best = delta;
                    result = i;
                }
            }
            return result;
        }
    }

    private static final class CellGrid {
        final int rowCount;
        final int colCount;
        final Cell[][] cellByRowCol;
        final List<Cell> cells;

        private CellGrid(int rowCount, int colCount, Cell[][] cellByRowCol, List<Cell> cells) {
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.cellByRowCol = cellByRowCol;
            this.cells = cells;
        }

        static CellGrid build(TableGeometry geometry) {
            int rowCount = geometry.rowCount;
            int colCount = geometry.colCount;
            int total = rowCount * colCount;
            UnionFind union = new UnionFind(total);

            for (int r = 0; r < rowCount; r++) {
                double yStart = geometry.rowBoundaries[r];
                double yEnd = geometry.rowBoundaries[r + 1];
                for (int c = 0; c < colCount; c++) {
                    double xStart = geometry.colBoundaries[c];
                    double xEnd = geometry.colBoundaries[c + 1];
                    int index = r * colCount + c;

                    if (r < rowCount - 1) {
                        boolean hasLine = geometry.hasHorizontal(r + 1, xStart, xEnd);
                        if (!hasLine) {
                            union.union(index, (r + 1) * colCount + c);
                        }
                    }
                    if (c < colCount - 1) {
                        boolean hasLine = geometry.hasVertical(c + 1, yStart, yEnd);
                        if (!hasLine) {
                            union.union(index, r * colCount + (c + 1));
                        }
                    }
                }
            }

            Map<Integer, Cell> cellByRoot = new LinkedHashMap<>();
            for (int r = 0; r < rowCount; r++) {
                for (int c = 0; c < colCount; c++) {
                    int index = r * colCount + c;
                    int root = union.find(index);
                    Cell cell = cellByRoot.computeIfAbsent(root, key -> new Cell());
                    cell.include(r, c);
                }
            }

            Cell[][] cellByRowCol = new Cell[rowCount][colCount];
            for (Cell cell : cellByRoot.values()) {
                cell.finalizeBounds(geometry);
                for (int r = cell.rowStart; r <= cell.rowEnd; r++) {
                    for (int c = cell.colStart; c <= cell.colEnd; c++) {
                        cellByRowCol[r][c] = cell;
                    }
                }
            }
            return new CellGrid(rowCount, colCount, cellByRowCol, new ArrayList<>(cellByRoot.values()));
        }

        Cell cellAt(int row, int col) {
            if (row < 0 || col < 0 || row >= cellByRowCol.length || col >= cellByRowCol[row].length) {
                return null;
            }
            return cellByRowCol[row][col];
        }

        void attachToRowSlices(List<RowSlice> rows) {
            // currently nothing extra to attach; placeholder for future metadata if needed.
        }
    }

    private static final class Cell {
        int rowStart = Integer.MAX_VALUE;
        int rowEnd = Integer.MIN_VALUE;
        int colStart = Integer.MAX_VALUE;
        int colEnd = Integer.MIN_VALUE;
        Rect bounds;
        final Map<Integer, List<TextFragment>> fragmentsByColumn = new HashMap<>();
        boolean checkboxTrueObserved = false;
        boolean checkboxFalseObserved = false;

        void include(int row, int col) {
            rowStart = Math.min(rowStart, row);
            rowEnd = Math.max(rowEnd, row);
            colStart = Math.min(colStart, col);
            colEnd = Math.max(colEnd, col);
        }

        void finalizeBounds(TableGeometry geometry) {
            int x = (int) Math.floor(geometry.colBoundaries[colStart]);
            int y = (int) Math.floor(geometry.rowBoundaries[rowStart]);
            int width = (int) Math.ceil(geometry.colBoundaries[colEnd + 1] - geometry.colBoundaries[colStart]);
            int height = (int) Math.ceil(geometry.rowBoundaries[rowEnd + 1] - geometry.rowBoundaries[rowStart]);
            this.bounds = new Rect(x, y, Math.max(1, width), Math.max(1, height));
        }

        void addFragment(int column, TextFragment fragment) {
            fragmentsByColumn.computeIfAbsent(column, key -> new ArrayList<>()).add(fragment);
        }

        void observeCheckbox(boolean checked) {
            if (checked) {
                checkboxTrueObserved = true;
            } else {
                checkboxFalseObserved = true;
            }
        }

        String getText(int column) {
            List<TextFragment> fragments = fragmentsByColumn.get(column);
            return combineFragments(fragments);
        }
    }

    private static final class RowSlice {
        final int index;
        final int top;
        final int bottom;
        final Map<Integer, List<TextFragment>> fragmentsByColumn = new HashMap<>();
        final Map<Integer, String> overrides = new HashMap<>();
        Boolean checkbox;

        RowSlice(int index, int top, int bottom) {
            this.index = index;
            this.top = top;
            this.bottom = bottom;
        }

        static List<RowSlice> create(TableGeometry geometry) {
            List<RowSlice> slices = new ArrayList<>();
            for (int i = 0; i < geometry.rowCount; i++) {
                int top = (int) Math.floor(geometry.rowBoundaries[i]);
                int bottom = (int) Math.ceil(geometry.rowBoundaries[i + 1]);
                slices.add(new RowSlice(i, top, bottom));
            }
            return slices;
        }

        void addFragment(int column, TextFragment fragment) {
            fragmentsByColumn.computeIfAbsent(column, key -> new ArrayList<>()).add(fragment);
        }

        void setOverride(int column, String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            overrides.put(column, normalizeLines(text));
        }

        String getRawText(Integer column) {
            if (column == null || column < 0) {
                return "";
            }
            return combineFragments(fragmentsByColumn.get(column));
        }

        String getResolvedText(Integer column) {
            if (column == null || column < 0) {
                return "";
            }
            String override = overrides.get(column);
            if (override != null) {
                return override;
            }
            return getRawText(column);
        }

        void acceptCheckbox(boolean checked) {
            if (checkbox == null) {
                checkbox = checked;
            } else if (checked) {
                checkbox = Boolean.TRUE;
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

    private static final class ContentAssigner {

        static void assign(List<RowSlice> rows,
                           CellGrid cellGrid,
                           TableGeometry geometry,
                           Mat contentMat,
                           List<MatOfPoint> contentContours,
                           Tesseract tesseract) {

            Mat gray = toGray(contentMat);
            double medianRowHeight = geometry.medianRowHeight;
            Rect tableBounds = geometry.bounds;

            for (MatOfPoint contour : contentContours) {
                Rect rect = Imgproc.boundingRect(contour);
                if (!intersects(rect, tableBounds, 4)) {
                    continue;
                }
                int rowIndex = geometry.locateRow(rect.y + rect.height / 2.0);
                int colIndex = geometry.locateColumn(rect.x + rect.width / 2.0);
                if (rowIndex < 0 || colIndex < 0 || rowIndex >= rows.size() || colIndex >= geometry.colCount) {
                    continue;
                }

                RowSlice rowSlice = rows.get(rowIndex);
                Cell cell = cellGrid.cellAt(rowIndex, colIndex);

                if (isCheckboxCandidate(rect, medianRowHeight)) {
                    boolean checked = isCheckboxMarked(gray, rect);
                    rowSlice.acceptCheckbox(checked);
                    if (cell != null) {
                        cell.observeCheckbox(checked);
                    }
                    continue;
                }

                String ocr = performOcr(tesseract, contentMat, rect);
                TextFragment fragment = new TextFragment(rect, ocr);
                rowSlice.addFragment(colIndex, fragment);
                if (cell != null) {
                    cell.addFragment(colIndex, fragment);
                }
            }
            gray.release();
        }

        private static boolean intersects(Rect rect, Rect bounds, int margin) {
            return rect.x + rect.width > bounds.x - margin &&
                rect.x < bounds.x + bounds.width + margin &&
                rect.y + rect.height > bounds.y - margin &&
                rect.y < bounds.y + bounds.height + margin;
        }

        private static Mat toGray(Mat source) {
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

        private static String performOcr(Tesseract tess, Mat source, Rect rect) {
            Rect roi = clampRect(rect, source, OCR_MARGIN);
            Mat patch = new Mat(source, roi).clone();
            BufferedImage image = matToBufferedImage(patch);
            patch.release();
            try {
                String raw = tess.doOCR(image);
                return normalizeLines(raw);
            } catch (TesseractException e) {
                return "";
            }
        }
    }

    private static final class ColumnModel {
        private final ColumnType[] types;
        private final Map<ColumnType, Integer> indexByType;

        private ColumnModel(ColumnType[] types, Map<ColumnType, Integer> indexByType) {
            this.types = types;
            this.indexByType = indexByType;
        }

        static ColumnModel infer(List<RowSlice> rows, TableGeometry geometry) {
            int colCount = geometry.colCount;
            ColumnType[] mapping = new ColumnType[colCount];
            List<String> headerSamples = collectHeaderSamples(rows, colCount);

            List<ColumnType> priority = Arrays.asList(
                ColumnType.CHECK_INCLUDED,
                ColumnType.TAB,
                ColumnType.DOCUMENT,
                ColumnType.FORM_NUMBER,
                ColumnType.DELIVERY_REQUIREMENT
            );

            Set<Integer> assignedColumns = new java.util.HashSet<>();
            for (ColumnType type : priority) {
                int bestColumn = -1;
                int bestScore = 0;
                for (int col = 0; col < colCount; col++) {
                    if (mapping[col] != null) {
                        continue;
                    }
                    int score = score(type, headerSamples.get(col));
                    if (score > bestScore) {
                        bestScore = score;
                        bestColumn = col;
                    }
                }
                if (bestColumn >= 0 && bestScore > 0) {
                    mapping[bestColumn] = type;
                    assignedColumns.add(bestColumn);
                }
            }

            int priorityIndex = 0;
            for (int col = 0; col < colCount; col++) {
                if (mapping[col] != null) {
                    continue;
                }
                while (priorityIndex < priority.size() && indexOf(mapping, priority.get(priorityIndex)) >= 0) {
                    priorityIndex++;
                }
                if (priorityIndex < priority.size()) {
                    mapping[col] = priority.get(priorityIndex++);
                } else {
                    mapping[col] = ColumnType.UNKNOWN;
                }
            }

            Map<ColumnType, Integer> indexByType = new HashMap<>();
            for (int i = 0; i < mapping.length; i++) {
                if (!indexByType.containsKey(mapping[i])) {
                    indexByType.put(mapping[i], i);
                }
            }
            return new ColumnModel(mapping, indexByType);
        }

        ColumnType typeAt(int column) {
            if (column < 0 || column >= types.length) {
                return ColumnType.UNKNOWN;
            }
            return types[column];
        }

        Integer indexOf(ColumnType type) {
            return indexByType.get(type);
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

        private static int score(ColumnType type, String text) {
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

        private static int indexOf(ColumnType[] array, ColumnType type) {
            for (int i = 0; i < array.length; i++) {
                if (array[i] == type) {
                    return i;
                }
            }
            return -1;
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

    private static final class Propagator {
        static void propagate(List<RowSlice> rows, ColumnModel columns, CellGrid grid) {
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
                    Cell cell = grid.cellAt(r, columnIndex);
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
    }

    private static final class RowAssembler {

        static List<RowRecord> build(List<RowSlice> rows, ColumnModel columns) {
            int start = findFirstDataRow(rows, columns);
            if (start >= rows.size()) {
                return Collections.emptyList();
            }

            Integer tabIdx = columns.indexOf(ColumnType.TAB);
            Integer docIdx = columns.indexOf(ColumnType.DOCUMENT);
            Integer formIdx = columns.indexOf(ColumnType.FORM_NUMBER);
            Integer deliveryIdx = columns.indexOf(ColumnType.DELIVERY_REQUIREMENT);

            List<RowRecord> records = new ArrayList<>();
            for (int i = start; i < rows.size(); i++) {
                RowSlice row = rows.get(i);
                String document = normalizeLines(row.getResolvedText(docIdx));
                String formNumber = normalizeLines(row.getResolvedText(formIdx));
                String delivery = normalizeLines(row.getResolvedText(deliveryIdx));
                String tab = normalizeLines(row.getResolvedText(tabIdx));
                boolean included = row.hasCheckbox() ? row.checkboxValue() : false;

                if (document.isEmpty() && formNumber.isEmpty() && delivery.isEmpty() && tab.isEmpty() && !row.hasCheckbox()) {
                    continue;
                }

                records.add(new RowRecord(included, tab, document, formNumber, delivery));
            }
            return Collections.unmodifiableList(records);
        }

        private static int findFirstDataRow(List<RowSlice> rows, ColumnModel columns) {
            int docIdx = columns.indexOf(ColumnType.DOCUMENT) == null ? -1 : columns.indexOf(ColumnType.DOCUMENT);
            int formIdx = columns.indexOf(ColumnType.FORM_NUMBER) == null ? -1 : columns.indexOf(ColumnType.FORM_NUMBER);
            int deliveryIdx = columns.indexOf(ColumnType.DELIVERY_REQUIREMENT) == null ? -1 : columns.indexOf(ColumnType.DELIVERY_REQUIREMENT);

            int idx = 0;
            while (idx < rows.size()) {
                RowSlice row = rows.get(idx);
                if (row.hasCheckbox()) {
                    break;
                }
                String doc = row.getRawText(docIdx).toUpperCase(Locale.ROOT);
                String form = row.getRawText(formIdx).toUpperCase(Locale.ROOT);
                String delivery = row.getRawText(deliveryIdx).toUpperCase(Locale.ROOT);
                boolean headerLike = (doc.contains("DOCUMENT") && form.contains("FORM")) ||
                    (doc.contains("CHECK") && delivery.contains("DELIVERY"));
                if (!headerLike) {
                    break;
                }
                idx++;
            }
            return idx;
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final int[] rank;

        UnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
                rank[i] = 0;
            }
        }

        int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]);
            }
            return parent[x];
        }

        void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA == rootB) {
                return;
            }
            if (rank[rootA] < rank[rootB]) {
                parent[rootA] = rootB;
            } else if (rank[rootA] > rank[rootB]) {
                parent[rootB] = rootA;
            } else {
                parent[rootB] = rootA;
                rank[rootA]++;
            }
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

    private static String combineFragments(List<TextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        List<TextFragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingInt(f -> f.rect.y));
        StringBuilder builder = new StringBuilder();
        int prevBottom = Integer.MIN_VALUE;
        for (TextFragment fragment : sorted) {
            String text = normalizeLines(fragment.text);
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                if (fragment.rect.y > prevBottom + 4) {
                    builder.append('\n');
                } else {
                    builder.append(' ');
                }
            }
            builder.append(text);
            prevBottom = fragment.rect.y + fragment.rect.height;
        }
        return builder.toString().trim();
    }

    private static String normalizeLines(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        String[] lines = cleaned.split("\\R+");
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
