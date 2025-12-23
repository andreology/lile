package com.contentmanagement.forms.api.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/**
 * Utility that isolates text components inside supplied ROIs by removing noise/specs using
 * data-driven statistics extracted from connected components.
 */
public final class TextDetector {
    private static final Logger LOGGER = Logger.getLogger(TextDetector.class.getName());

    private TextDetector() {
    }

    /**
     * Cleans each ROI and returns a list of binary Mats that contain only the text components.
     *
     * @param src  input image (white background, black ink)
     * @param rois list of ROI polygons
     * @return list of cleaned binary Mats aligned to each ROI's bounding box
     */
    public static Mat cleanTextRegions(Mat src, List<MatOfPoint> rois) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(rois, "rois");
        Mat cleaned = Mat.zeros(src.size(), CvType.CV_8U);
        for (MatOfPoint roi : rois) {
            Mat roiMask = processRoi(src, roi);
            Rect bounds = Imgproc.boundingRect(roi);
            bounds = clampRect(bounds, cleaned.width(), cleaned.height());
            Mat destination = cleaned.submat(bounds);
            Core.max(destination, roiMask, destination);
            destination.release();
            roiMask.release();
        }
        return cleaned;
    }

    private static Mat processRoi(Mat src, MatOfPoint polygon) {
        Rect bounds = Imgproc.boundingRect(polygon);
        bounds = clampRect(bounds, src.width(), src.height());
        Mat mask = Mat.zeros(bounds.size(), CvType.CV_8U);
        MatOfPoint shifted = shiftPolygon(polygon, bounds.tl());
        Imgproc.fillPoly(mask, Collections.singletonList(shifted), new Scalar(255));

        Mat roi = src.submat(bounds);
        Mat gray = toGray(roi);
        Mat masked = new Mat();
        gray.copyTo(masked, mask);

        Mat binary = binarize(masked, mask);
        Mat cleaned = filterComponents(binary);
        Core.bitwise_and(cleaned, mask, cleaned);

        gray.release();
        masked.release();
        binary.release();
        mask.release();
        shifted.release();
        roi.release();
        return cleaned;
    }

    private static Mat toGray(Mat mat) {
        if (mat.channels() == 1) {
            return mat.clone();
        }
        Mat gray = new Mat();
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
        return gray;
    }

    private static Mat binarize(Mat src, Mat mask) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new org.opencv.core.Size(3, 3), 0);
        Mat binary = new Mat();
        int adaptiveSize = computeAdaptiveSize(src.size());
        Imgproc.adaptiveThreshold(blurred, binary, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, adaptiveSize, adaptiveSize * 0.1);
        Core.bitwise_and(binary, mask, binary);
        double nonZero = Core.countNonZero(binary);
        double total = Core.countNonZero(mask);
        if (total > 0 && nonZero / total > 0.75) {
            Core.bitwise_not(binary, binary);
            Core.bitwise_and(binary, mask, binary);
        }
        blurred.release();
        return binary;
    }

    private static int computeAdaptiveSize(org.opencv.core.Size size) {
        int minDim = (int) Math.max(3, Math.min(size.width, size.height) / 4);
        if ((minDim & 1) == 0) {
            minDim += 1;
        }
        return Math.max(3, minDim);
    }

    private static Mat filterComponents(Mat binary) {
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int numLabels = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids);
        if (numLabels <= 1) {
            labels.release();
            stats.release();
            centroids.release();
            return binary.clone();
        }

        List<Component> components = new ArrayList<>(numLabels - 1);
        for (int label = 1; label < numLabels; label++) {
            int x = (int) stats.get(label, Imgproc.CC_STAT_LEFT)[0];
            int y = (int) stats.get(label, Imgproc.CC_STAT_TOP)[0];
            int width = (int) stats.get(label, Imgproc.CC_STAT_WIDTH)[0];
            int height = (int) stats.get(label, Imgproc.CC_STAT_HEIGHT)[0];
            int area = (int) stats.get(label, Imgproc.CC_STAT_AREA)[0];
            double cx = centroids.get(label, 0)[0];
            double cy = centroids.get(label, 1)[0];
            Rect box = new Rect(x, y, width, height);
            double density = area / Math.max(1.0, width * height);
            components.add(new Component(label, area, width, height, density, cx, cy, box));
        }

        if (components.isEmpty()) {
            labels.release();
            stats.release();
            centroids.release();
            return binary.clone();
        }

        double[] areas = components.stream().mapToDouble(c -> c.area).toArray();
        double[] heights = components.stream().mapToDouble(c -> c.height).toArray();
        double[] widths = components.stream().mapToDouble(c -> c.width).toArray();
        double[] densities = components.stream().mapToDouble(c -> c.density).toArray();
        double[] centersY = components.stream().mapToDouble(c -> c.centerY).toArray();

        Statistics statsHelper = new Statistics();
        double medianArea = statsHelper.median(areas);
        double medianHeight = statsHelper.median(heights);
        double medianWidth = statsHelper.median(widths);
        double medianDensity = statsHelper.median(densities);

        Thresholds thresholds = new Thresholds(
            areas, widths, heights, densities, centersY,
            medianArea, medianWidth, medianHeight, medianDensity);

        boolean[] keep = new boolean[numLabels];
        int keptCount = 0;
        for (Component component : components) {
            boolean candidate = thresholds.isWithin(component);
            if (!candidate && thresholds.shouldRescue(component, components)) {
                candidate = true;
            }
            keep[component.label] = candidate;
            if (candidate) {
                LOGGER.log(Level.FINEST, () -> String.format(
                    "KEEP label=%d area=%.1f w=%.1f h=%.1f density=%.3f centerY=%.1f",
                    component.label, component.area, component.width,
                    component.height, component.density, component.centerY));
                keptCount++;
            } else {
                LOGGER.log(Level.FINER, () -> String.format(
                    "DROP label=%d area=%.1f w=%.1f h=%.1f density=%.3f centerY=%.1f",
                    component.label, component.area, component.width,
                    component.height, component.density, component.centerY));
            }
        }

        if (keptCount == 0) {
            Component largest = components.stream()
                .max((a, b) -> Double.compare(a.area, b.area))
                .orElse(null);
            if (largest != null) {
                keep[largest.label] = true;
                keptCount = 1;
                LOGGER.log(Level.WARNING,
                    () -> String.format("All components rejected; rescuing label=%d area=%.1f",
                        largest.label, largest.area));
            }
        }

        Mat cleaned = Mat.zeros(binary.size(), CvType.CV_8U);
        for (Component component : components) {
            if (!keep[component.label]) {
                continue;
            }
            Mat mask = new Mat();
            Core.compare(labels, new Scalar(component.label), mask, Core.CMP_EQ);
            cleaned.setTo(new Scalar(255), mask);
            mask.release();
        }

        labels.release();
        stats.release();
        centroids.release();
        return cleaned;
    }

    private static Rect clampRect(Rect rect, int maxWidth, int maxHeight) {
        int x = Math.min(Math.max(0, rect.x), Math.max(0, maxWidth - 1));
        int y = Math.min(Math.max(0, rect.y), Math.max(0, maxHeight - 1));
        int width = Math.min(rect.width, Math.max(1, maxWidth - x));
        int height = Math.min(rect.height, Math.max(1, maxHeight - y));
        return new Rect(x, y, Math.max(1, width), Math.max(1, height));
    }

    private static MatOfPoint shiftPolygon(MatOfPoint polygon, Point offset) {
        Point[] points = polygon.toArray();
        Point[] shifted = new Point[points.length];
        for (int i = 0; i < points.length; i++) {
            shifted[i] = new Point(points[i].x - offset.x, points[i].y - offset.y);
        }
        MatOfPoint mat = new MatOfPoint();
        mat.fromArray(shifted);
        return mat;
    }

    private static final class Component {
        final int label;
        final double area;
        final double width;
        final double height;
        final double density;
        final double centerX;
        final double centerY;
        final Rect box;

        Component(int label, double area, double width, double height, double density,
                  double centerX, double centerY, Rect box) {
            this.label = label;
            this.area = area;
            this.width = width;
            this.height = height;
            this.density = density;
            this.centerX = centerX;
            this.centerY = centerY;
            this.box = box;
        }
    }

    private static final class Thresholds {
        private final double areaLower;
        private final double areaUpper;
        private final double heightLower;
        private final double heightUpper;
        private final double widthLower;
        private final double widthUpper;
        private final double densityLower;
        private final double densityUpper;
        private final double centerYLower;
        private final double centerYUpper;
        private final double medianArea;
        private final double medianHeight;

        Thresholds(double[] areas, double[] widths, double[] heights,
                   double[] densities, double[] centersY,
                   double medianArea, double medianWidth,
                   double medianHeight, double medianDensity) {
            Statistics stats = new Statistics();
            this.medianArea = medianArea;
            this.medianHeight = medianHeight;

            double areaLow = stats.percentile(areas, 10);
            double areaHigh = stats.percentile(areas, 90);
            double heightLow = stats.percentile(heights, 10);
            double heightHigh = stats.percentile(heights, 90);
            double widthLow = stats.percentile(widths, 10);
            double widthHigh = stats.percentile(widths, 90);
            double densityLow = stats.percentile(densities, 10);
            double densityHigh = stats.percentile(densities, 90);
            double centerLow = stats.percentile(centersY, 15);
            double centerHigh = stats.percentile(centersY, 85);

            if (Double.compare(areaLow, areaHigh) == 0) {
                areaLow = Math.min(areaLow, medianArea);
                areaHigh = Math.max(areaHigh, medianArea);
            }
            if (Double.compare(heightLow, heightHigh) == 0) {
                heightLow = Math.min(heightLow, medianHeight);
                heightHigh = Math.max(heightHigh, medianHeight);
            }
            if (Double.compare(widthLow, widthHigh) == 0) {
                widthLow = Math.min(widthLow, medianWidth);
                widthHigh = Math.max(widthHigh, medianWidth);
            }
            if (Double.compare(densityLow, densityHigh) == 0) {
                densityLow = Math.min(densityLow, medianDensity);
                densityHigh = Math.max(densityHigh, medianDensity);
            }
            if (Double.compare(centerLow, centerHigh) == 0) {
                double centerMedian = stats.median(centersY);
                centerLow = Math.min(centerLow, centerMedian);
                centerHigh = Math.max(centerHigh, centerMedian);
            }

            this.areaLower = areaLow;
            this.areaUpper = areaHigh;
            this.heightLower = heightLow;
            this.heightUpper = heightHigh;
            this.widthLower = widthLow;
            this.widthUpper = widthHigh;
            this.densityLower = densityLow;
            this.densityUpper = densityHigh;
            this.centerYLower = centerLow;
            this.centerYUpper = centerHigh;
        }

        boolean isWithin(Component component) {
            return component.area >= areaLower && component.area <= areaUpper
                && component.height >= heightLower && component.height <= heightUpper
                && component.width >= widthLower && component.width <= widthUpper
                && component.density >= densityLower && component.density <= densityUpper
                && component.centerY >= centerYLower && component.centerY <= centerYUpper;
        }

        boolean shouldRescue(Component component, List<Component> components) {
            if (component.area >= areaLower || component.area >= medianArea) {
                return false;
            }
            for (Component candidate : components) {
                if (candidate == component || candidate.area < medianArea) {
                    continue;
                }
                double overlap = horizontalOverlap(component.box, candidate.box);
                boolean verticalAligned = candidate.centerY > component.centerY
                    && candidate.centerY - component.centerY <= medianHeight * 1.8;
                if (overlap >= 0.35 && verticalAligned) {
                    return true;
                }
            }
            return false;
        }

        private double horizontalOverlap(Rect a, Rect b) {
            int left = Math.max(a.x, b.x);
            int right = Math.min(a.x + a.width, b.x + b.width);
            if (right <= left) {
                return 0.0;
            }
            double overlap = right - left;
            return overlap / Math.min(a.width, b.width);
        }
    }

    private static final class Statistics {
        double median(double[] values) {
            if (values.length == 0) {
                return 0.0;
            }
            double[] copy = Arrays.copyOf(values, values.length);
            Arrays.sort(copy);
            int mid = copy.length / 2;
            if (copy.length % 2 == 0) {
                return (copy[mid - 1] + copy[mid]) / 2.0;
            }
            return copy[mid];
        }

        double mad(double[] values, double median) {
            if (values.length == 0) {
                return 0.0;
            }
            double[] deviations = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                deviations[i] = Math.abs(values[i] - median);
            }
            return median(deviations);
        }

        double percentile(double[] values, double percentile) {
            if (values.length == 0) {
                return 0.0;
            }
            double[] copy = Arrays.copyOf(values, values.length);
            Arrays.sort(copy);
            if (copy.length == 1) {
                return copy[0];
            }
            double rank = percentile / 100.0 * (copy.length - 1);
            int lower = (int) Math.floor(rank);
            int upper = (int) Math.ceil(rank);
            double weight = rank - lower;
            return copy[lower] + weight * (copy[upper] - copy[lower]);
        }
    }
}
