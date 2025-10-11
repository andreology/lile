package com.contentmanagement.forms.api.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

/**
 * JavaFX helper to experiment with OpenCV parameters on a {@link Mat} image.
 */
public class CvImageHelper extends Application {

    private static final AtomicBoolean FX_INITIALISED = new AtomicBoolean(false);
    private static volatile Mat sharedSourceMat;
    private static volatile CvImageHelper currentInstance;

    private static void ensureOpenCvLoaded() {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError ignore) {
            // assume already loaded elsewhere
        }
    }

    public static void show(Mat mat) {
        if (mat == null || mat.empty()) {
            throw new IllegalArgumentException("Provided Mat is null or empty");
        }
        ensureOpenCvLoaded();
        sharedSourceMat = mat.clone();
        if (FX_INITIALISED.compareAndSet(false, true)) {
            new Thread(() -> Application.launch(CvImageHelper.class)).start();
        } else {
            Platform.runLater(() -> {
                if (currentInstance == null) {
                    try {
                        CvImageHelper helper = new CvImageHelper();
                        helper.start(new Stage());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                } else {
                    currentInstance.replaceImage(sharedSourceMat.clone());
                }
            });
        }
    }

    public static void main(String[] args) {
        ensureOpenCvLoaded();
        if (args.length > 0) {
            Mat candidate = Imgcodecs.imread(args[0]);
            if (!candidate.empty()) {
                sharedSourceMat = candidate;
            }
        }
        launch(args);
    }

    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cv-image-helper-processor");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Future<?>> activeTask = new AtomicReference<>();

    private final DoubleProperty originalZoom = new SimpleDoubleProperty(1.0);
    private final DoubleProperty processedZoom = new SimpleDoubleProperty(1.0);
    private final DecimalFormat decimalFormat = new DecimalFormat("0.##");

    private Stage primaryStage;
    private ImageView originalView;
    private ImageView processedView;

    private Slider blurSlider;
    private Slider blockSizeSlider;
    private Slider cOffsetSlider;
    private Slider globalThresholdSlider;
    private Slider dilateKernelXSlider;
    private Slider dilateKernelYSlider;
    private Slider dilateIterationsSlider;
    private Slider erodeIterationsSlider;
    private Slider underlineLengthSlider;
    private Slider underlineThicknessSlider;
    private Slider contourMinAreaSlider;
    private Slider bilateralDiameterSlider;
    private Slider bilateralSigmaColorSlider;
    private Slider bilateralSigmaSpaceSlider;
    private Slider textRemovalMinAreaSlider;
    private Spinner<Integer> inpaintSpinner;

    private CheckBox adaptiveCheck;
    private CheckBox invertCheck;
    private CheckBox removeUnderlineCheck;
    private CheckBox dilateCheck;
    private CheckBox erodeCheck;
    private CheckBox morphOpenCheck;
    private CheckBox morphCloseCheck;
    private CheckBox showContoursCheck;
    private CheckBox equalizeHistCheck;
    private CheckBox bilateralCheck;
    private CheckBox removeTextCheck;

    private Label contourInfoLabel;
    private Label zoomLabel;

    private Mat sourceMat;

    @Override
    public void start(Stage stage) {
        currentInstance = this;
        this.primaryStage = stage;
        this.sourceMat = (sharedSourceMat == null || sharedSourceMat.empty())
                ? requestImageFromUser(stage)
                : sharedSourceMat.clone();

        if (sourceMat == null || sourceMat.empty()) {
            throw new IllegalStateException("No image available for processing – supply a Mat or choose a file.");
        }

        originalView = createImageView(originalZoom);
        processedView = createImageView(processedZoom);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setCenter(createImagePane());
        root.setRight(createControlPane());

        updateImageView(originalView, sourceMat);
        applyProcessing();

        Scene scene = new Scene(root, 1600, 860);
        stage.setTitle("OpenCV Image Helper");
        stage.setScene(scene);
        stage.show();
        stage.setOnCloseRequest(evt -> currentInstance = null);
    }

    @Override
    public void stop() {
        Future<?> task = activeTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
        processingExecutor.shutdownNow();
        currentInstance = null;
    }

    private void replaceImage(Mat mat) {
        if (mat == null || mat.empty()) {
            return;
        }
        if (sourceMat != null) {
            sourceMat.release();
        }
        sourceMat = mat;
        sharedSourceMat = mat.clone();
        originalZoom.set(1.0);
        processedZoom.set(1.0);
        updateImageView(originalView, sourceMat);
        applyProcessing();
        if (primaryStage != null && !primaryStage.isShowing()) {
            primaryStage.show();
        }
    }

    private Mat requestImageFromUser(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.tif", "*.tiff", "*.bmp")
        );
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return null;
        }
        Mat mat = Imgcodecs.imread(file.getAbsolutePath());
        if (mat.empty()) {
            throw new IllegalStateException("Unable to load image from " + file.getAbsolutePath());
        }
        return mat;
    }

    private ScrollPane createImagePane() {
        HBox hBox = new HBox(
                10,
                wrapWithTitledPane("Original", originalView, originalZoom),
                wrapWithTitledPane("Processed", processedView, processedZoom)
        );
        hBox.setAlignment(Pos.CENTER);
        ScrollPane scrollPane = new ScrollPane(hBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        return scrollPane;
    }

    private TitledPane wrapWithTitledPane(String title, ImageView imageView, DoubleProperty zoomProperty) {
        StackPane content = new StackPane(imageView);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(10));

        content.addEventFilter(ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaY() > 0 ? 0.1 : -0.1;
            double newZoom = clamp(zoomProperty.get() + delta, 0.25, 6.0);
            zoomProperty.set(newZoom);
            event.consume();
        });
        content.setOnMouseClicked(evt -> {
            if (evt.getClickCount() == 2) {
                zoomProperty.set(1.0);
            }
        });

        TitledPane titledPane = new TitledPane(title, content);
        titledPane.setCollapsible(false);
        return titledPane;
    }

    private ImageView createImageView(DoubleProperty zoomProperty) {
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.scaleXProperty().bind(zoomProperty);
        view.scaleYProperty().bind(zoomProperty);
        return view;
    }

    private VBox createControlPane() {
        VBox container = new VBox(12);
        container.setPadding(new Insets(10));
        container.setPrefWidth(380);

        Button loadButton = new Button("Load Image...");
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setOnAction(evt -> {
            Mat loaded = requestImageFromUser(primaryStage);
            if (loaded != null && !loaded.empty()) {
                replaceImage(loaded);
            }
        });

        Button resetButton = new Button("Reset to Original");
        resetButton.setMaxWidth(Double.MAX_VALUE);
        resetButton.setOnAction(evt -> {
            if (sharedSourceMat != null && !sharedSourceMat.empty()) {
                replaceImage(sharedSourceMat.clone());
            }
        });

        container.getChildren().addAll(loadButton, resetButton, createSeparator());

        GridPane sliders = new GridPane();
        sliders.setHgap(8);
        sliders.setVgap(8);
        sliders.setPadding(new Insets(5));

        int row = 0;
        blurSlider = createSlider(sliders, row++, "Blur (odd kernel)", 1, 15, 1, true);
        blockSizeSlider = createSlider(sliders, row++, "Adaptive block", 3, 51, 15, true);
        cOffsetSlider = createSlider(sliders, row++, "Adaptive C", -20, 20, 3, false);
        globalThresholdSlider = createSlider(sliders, row++, "Global threshold", 0, 255, 128, true);
        underlineLengthSlider = createSlider(sliders, row++, "Underline length", 5, 200, 80, true);
        underlineThicknessSlider = createSlider(sliders, row++, "Underline thickness", 1, 15, 2, true);
        dilateKernelXSlider = createSlider(sliders, row++, "Kernel width", 1, 25, 3, true);
        dilateKernelYSlider = createSlider(sliders, row++, "Kernel height", 1, 25, 3, true);
        dilateIterationsSlider = createSlider(sliders, row++, "Dilate iter", 0, 10, 1, true);
        erodeIterationsSlider = createSlider(sliders, row++, "Erode iter", 0, 10, 0, true);
        contourMinAreaSlider = createSlider(sliders, row++, "Contour min area", 10, 5000, 400, true);

        bilateralDiameterSlider = createSlider(sliders, row++, "Bilateral diameter", 3, 15, 7, true);
        bilateralSigmaColorSlider = createSlider(sliders, row++, "Bilateral sigmaColor", 10, 150, 75, false);
        bilateralSigmaSpaceSlider = createSlider(sliders, row++, "Bilateral sigmaSpace", 10, 150, 75, false);
        textRemovalMinAreaSlider = createSlider(sliders, row++, "Text min area", 10, 3000, 250, true);

        inpaintSpinner = new Spinner<>();
        inpaintSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 15, 3));
        inpaintSpinner.valueProperty().addListener((obs, oldV, newV) -> applyProcessing());
        sliders.add(new Label("Inpaint radius"), 0, row);
        sliders.add(inpaintSpinner, 1, row++);

        container.getChildren().add(new TitledPane("Parameters", sliders));

        VBox toggles = new VBox(8);
        toggles.setPadding(new Insets(5));
        adaptiveCheck = createToggle("Use adaptive threshold", true, toggles);
        invertCheck = createToggle("Invert result", true, toggles);
        equalizeHistCheck = createToggle("Histogram equalize", false, toggles);
        bilateralCheck = createToggle("Apply bilateral filter", false, toggles);
        removeUnderlineCheck = createToggle("Remove horizontal underlines", true, toggles);
        removeTextCheck = createToggle("Remove text regions", false, toggles);
        dilateCheck = createToggle("Apply dilation", true, toggles);
        erodeCheck = createToggle("Apply erosion", false, toggles);
        morphOpenCheck = createToggle("Morph open", false, toggles);
        morphCloseCheck = createToggle("Morph close", false, toggles);
        showContoursCheck = createToggle("Draw contours", true, toggles);

        container.getChildren().add(new TitledPane("Toggles", toggles));

        zoomLabel = new Label("1.0x");
        Slider zoomSlider = new Slider(0.25, 4.0, 1.0);
        zoomSlider.setBlockIncrement(0.1);
        zoomSlider.valueProperty().addListener((obs, oldV, newV) -> {
            double zoom = newV.doubleValue();
            originalZoom.set(zoom);
            processedZoom.set(zoom);
            zoomLabel.setText(decimalFormat.format(zoom) + "x");
        });
        Button resetZoom = new Button("Reset zoom");
        resetZoom.setOnAction(evt -> zoomSlider.setValue(1.0));

        VBox zoomBox = new VBox(6, zoomLabel, zoomSlider, resetZoom);
        zoomBox.setPadding(new Insets(5));
        container.getChildren().add(new TitledPane("Zoom", zoomBox));

        contourInfoLabel = new Label("Contours: --");
        container.getChildren().addAll(createSeparator(), contourInfoLabel, createSpacer());

        return container;
    }

    private Region createSeparator() {
        Region sep = new Region();
        sep.setPrefHeight(6);
        sep.setStyle("-fx-background-color: #c5c5c5;");
        VBox.setMargin(sep, new Insets(6, 0, 6, 0));
        return sep;
    }

    private Region createSpacer() {
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private Slider createSlider(GridPane grid, int row, String label, double min, double max, double initial, boolean whole) {
        Label sliderLabel = new Label(label);
        Slider slider = new Slider(min, max, initial);
        slider.setShowTickMarks(false);
        slider.setShowTickLabels(false);
        slider.setBlockIncrement(whole ? 1 : 0.5);
        slider.setMajorTickUnit((max - min) / 4.0);
        Label valueLabel = new Label(decimalFormat.format(initial));

        ChangeListener<Number> listener = (obs, oldVal, newVal) -> {
            if (whole) {
                double rounded = Math.round(newVal.doubleValue());
                slider.setValue(rounded);
                valueLabel.setText(Long.toString(Math.round(rounded)));
            } else {
                valueLabel.setText(decimalFormat.format(newVal.doubleValue()));
            }
            applyProcessing();
        };
        slider.valueProperty().addListener(listener);

        grid.add(sliderLabel, 0, row);
        grid.add(slider, 1, row);
        grid.add(valueLabel, 2, row);
        GridPane.setHgrow(slider, Priority.ALWAYS);
        return slider;
    }

    private CheckBox createToggle(String text, boolean initial, VBox container) {
        CheckBox checkBox = new CheckBox(text);
        checkBox.setSelected(initial);
        checkBox.selectedProperty().addListener((obs, oldV, newV) -> applyProcessing());
        container.getChildren().add(checkBox);
        return checkBox;
    }

    private void applyProcessing() {
        if (sourceMat == null || sourceMat.empty()) {
            return;
        }
        Mat clone = sourceMat.clone();
        Future<?> previous = activeTask.getAndSet(processingExecutor.submit(() -> {
            try {
                ProcessingResult result = process(clone);
                Image processedImage = matToImage(result.outputMat);
                result.outputMat.release();
                Platform.runLater(() -> {
                    processedView.setImage(processedImage);
                    contourInfoLabel.setText(result.summary);
                });
            } finally {
                clone.release();
            }
        }));
        if (previous != null) {
            previous.cancel(true);
        }
    }

    private ProcessingResult process(Mat working) {
        Mat gray = new Mat();
        if (working.channels() > 1) {
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = working.clone();
        }

        if (equalizeHistCheck.isSelected()) {
            Imgproc.equalizeHist(gray, gray);
        }

        if (bilateralCheck.isSelected()) {
            Imgproc.bilateralFilter(gray,
                    gray,
                    (int) Math.max(1, bilateralDiameterSlider.getValue()),
                    bilateralSigmaColorSlider.getValue(),
                    bilateralSigmaSpaceSlider.getValue());
        } else {
            int blurKernel = (int) blurSlider.getValue();
            if (blurKernel % 2 == 0) {
                blurKernel++;
            }
            if (blurKernel > 1) {
                Imgproc.GaussianBlur(gray, gray, new Size(blurKernel, blurKernel), 0);
            }
        }

        Mat binary = new Mat();
        if (adaptiveCheck.isSelected()) {
            int blockSize = (int) blockSizeSlider.getValue();
            if (blockSize % 2 == 0) {
                blockSize++;
            }
            blockSize = Math.max(3, blockSize);
            double c = cOffsetSlider.getValue();
            Imgproc.adaptiveThreshold(gray, binary, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    invertCheck.isSelected() ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY,
                    blockSize, c);
        } else {
            double thresh = globalThresholdSlider.getValue();
            int mode = invertCheck.isSelected() ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY;
            Imgproc.threshold(gray, binary, thresh, 255, mode);
        }

        if (removeUnderlineCheck.isSelected()) {
            Mat cleaned = removeHorizontalArtifacts(binary,
                    (int) underlineLengthSlider.getValue(),
                    (int) underlineThicknessSlider.getValue(),
                    inpaintSpinner.getValue());
            binary.release();
            binary = cleaned;
        }

        if (removeTextCheck.isSelected()) {
            Mat textCleared = removeTextRegions(binary, textRemovalMinAreaSlider.getValue());
            binary.release();
            binary = textCleared;
        }

        Size kernelSize = new Size(Math.max(1, (int) dilateKernelXSlider.getValue()),
                Math.max(1, (int) dilateKernelYSlider.getValue()));
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kernelSize);

        if (morphOpenCheck.isSelected()) {
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel);
        }
        if (morphCloseCheck.isSelected()) {
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
        }
        if (dilateCheck.isSelected() && dilateIterationsSlider.getValue() > 0) {
            Imgproc.dilate(binary, binary, kernel, new Point(-1, -1), (int) dilateIterationsSlider.getValue());
        }
        if (erodeCheck.isSelected() && erodeIterationsSlider.getValue() > 0) {
            Imgproc.erode(binary, binary, kernel, new Point(-1, -1), (int) erodeIterationsSlider.getValue());
        }

        Mat display = new Mat();
        Imgproc.cvtColor(binary, display, Imgproc.COLOR_GRAY2BGR);

        String summary;
        if (showContoursCheck.isSelected()) {
            int count = drawContours(binary, display, contourMinAreaSlider.getValue());
            summary = "Contours: " + count + " (min area " + decimalFormat.format(contourMinAreaSlider.getValue()) + ")";
        } else {
            summary = "Contours disabled";
        }

        gray.release();
        binary.release();
        kernel.release();

        return new ProcessingResult(display, summary);
    }

    private Mat removeHorizontalArtifacts(Mat binary, int length, int thickness, int inpaintRadius) {
        Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(Math.max(1, length), Math.max(1, thickness)));
        Mat detectedLines = new Mat();
        Imgproc.morphologyEx(binary, detectedLines, Imgproc.MORPH_OPEN, horizontalKernel);

        Mat cleaned = new Mat();
        Core.subtract(binary, detectedLines, cleaned);

        if (inpaintRadius > 0) {
            Mat mask = new Mat();
            Imgproc.threshold(detectedLines, mask, 0, 255, Imgproc.THRESH_BINARY);
            Photo.inpaint(cleaned, mask, cleaned, inpaintRadius, Photo.INPAINT_NS);
            mask.release();
        }

        detectedLines.release();
        horizontalKernel.release();
        return cleaned;
    }

   private Mat removeTextRegions(Mat binary, double minArea) {
        Mat result = binary.clone();
        Mat contourInput = binary.clone();
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area >= minArea) {
                Imgproc.drawContours(result, List.of(contour), -1, new Scalar(0), -1);
            }
            contour.release();
        }
        contourInput.release();
        hierarchy.release();
        return result;
    }

    private int drawContours(Mat binary, Mat display, double minArea) {
        Mat contourInput = binary.clone();
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        int count = 0;
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area >= minArea) {
                Rect rect = Imgproc.boundingRect(contour);
                Imgproc.rectangle(display, rect, new Scalar(0, 255, 0), 2);
                count++;
            }
            contour.release();
        }
        contourInput.release();
        hierarchy.release();
        return count;
    }

    private void updateImageView(ImageView view, Mat mat) {
        Image image = matToImage(mat);
        view.setImage(image);
    }

    private Image matToImage(Mat mat) {
        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".png", mat, buffer);
        Image img = new Image(new ByteArrayInputStream(buffer.toArray()));
        buffer.release();
        return img;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ProcessingResult {
        private final Mat outputMat;
        private final String summary;

        private ProcessingResult(Mat outputMat, String summary) {
            this.outputMat = outputMat;
            this.summary = summary;
        }
    }
}
