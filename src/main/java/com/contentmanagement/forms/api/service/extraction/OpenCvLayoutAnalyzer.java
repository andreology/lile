package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.model.WidgetType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenCvLayoutAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OpenCvLayoutAnalyzer.class);

    private static final double MIN_COMPONENT_AREA_RATIO = 0.0005;
    private static final double MAX_COMPONENT_AREA_RATIO = 0.8;

    public PageLayout analyze(Mat image, int pageIndex) {
        double width = image.width();
        double height = image.height();
        Mat processed = preprocess(image);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(processed, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        List<DetectedComponent> components = new ArrayList<>();
        int index = 0;
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.height <= 4 || rect.width <= 4) {
                continue;
            }
            double area = rect.area();
            double pageArea = width * height;
            double areaRatio = area / pageArea;
            if (areaRatio < MIN_COMPONENT_AREA_RATIO || areaRatio > MAX_COMPONENT_AREA_RATIO) {
                continue;
            }
            DetectedComponentType type = classify(rect);
            WidgetType widgetType = inferWidgetType(type, rect);
            double confidence = computeConfidence(processed, rect);
            components.add(new DetectedComponent(index++, type, rect, null, confidence, widgetType));
        }

        processed.release();
        hierarchy.release();

        components.sort(Comparator
                .comparingInt((DetectedComponent c) -> c.boundingBox().y)
                .thenComparingInt(c -> c.boundingBox().x));

        List<DetectedComponent> reindexed = new ArrayList<>(components.size());
        int order = 0;
        for (DetectedComponent component : components) {
            reindexed.add(new DetectedComponent(order++, component.type(), component.boundingBox(), component.text(), component.confidence(), component.widgetType()));
        }

        reindexed.stream()
                .filter(component -> component.type() == DetectedComponentType.GROUP)
                .forEach(component -> log.info(
                        "Group detected on page {} (#{}): bbox[x={},y={},w={},h={}] confidence={}",
                        pageIndex,
                        component.index(),
                        component.boundingBox().x,
                        component.boundingBox().y,
                        component.boundingBox().width,
                        component.boundingBox().height,
                        String.format(Locale.ROOT, "%.3f", component.confidence())));
        return new PageLayout(pageIndex, width, height, reindexed);
    }

    private Mat preprocess(Mat image) {
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Mat binary = new Mat();
        Imgproc.adaptiveThreshold(blurred, binary, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 35, 5);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
        gray.release();
        blurred.release();
        kernel.release();
        return binary;
    }

    private DetectedComponentType classify(Rect rect) {
        double aspectRatio = rect.width / (double) rect.height;
        if (aspectRatio >= 8 && rect.height < 80) {
            return DetectedComponentType.TEXT;
        }
        if (rect.width < 80 && rect.height < 80) {
            return DetectedComponentType.FIELD;
        }
        if (rect.width > rect.height * 4) {
            return DetectedComponentType.TEXT;
        }
        if (rect.height > rect.width * 1.5) {
            return DetectedComponentType.GROUP;
        }
        return DetectedComponentType.FIELD;
    }

    private WidgetType inferWidgetType(DetectedComponentType type, Rect rect) {
        if (type != DetectedComponentType.FIELD) {
            return null;
        }
        double aspectRatio = rect.width / (double) rect.height;
        if (aspectRatio < 1.2) {
            return WidgetType.CHECKBOX;
        }
        if (rect.height > 60) {
            return WidgetType.TEXTAREA;
        }
        return WidgetType.TEXT;
    }

    private double computeConfidence(Mat binaryImage, Rect rect) {
        Rect safeRect = sanitize(rect, binaryImage.width(), binaryImage.height());
        Mat roi = new Mat(binaryImage, safeRect);
        Mat roi8U = new Mat();
        roi.convertTo(roi8U, CvType.CV_8U);
        Scalar mean = Core.mean(roi8U);
        double norm = mean.val[0] / 255.0;
        roi8U.release();
        roi.release();
        return Math.min(1.0, Math.max(0.0, norm));
    }

    private Rect sanitize(Rect rect, double maxWidth, double maxHeight) {
        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int width = Math.min(rect.width, (int) Math.max(1, maxWidth - x));
        int height = Math.min(rect.height, (int) Math.max(1, maxHeight - y));
        return new Rect(x, y, width, height);
    }
}
