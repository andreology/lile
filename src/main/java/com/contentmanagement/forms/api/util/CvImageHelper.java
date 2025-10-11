package com.contentmanagement.forms.api.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

/**
 * JavaFX helper to experiment with OpenCV parameters on top of a {@link Mat} image.
 */
public class CvImageHelper extends Application {

    private static final AtomicBoolean FX_INITIALISED = new AtomicBoolean(false);
    private static volatile Mat sharedSourceMat;
    private static volatile CvImageHelper currentInstance;

    private static void ensureOpenCvLoaded() {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError ignore) {
            // assume already loaded
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
                if (currentInstance != null) {
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
    private Spinner<Integer> inpaintSpinner;

    private CheckBox adaptiveCheck;
    private CheckBox invertCheck;
    private CheckBox removeUnderlineCheck;
    private CheckBox dilateCheck;
    private CheckBox erodeCheck;
    private CheckBox morphOpenCheck;
    private CheckBox morphCloseCheck;
    private CheckBox showContoursCheck;

    private Label contourInfoLabel;

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

        originalView = createImageView();
        processedView = createImageView();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setCenter(createImagePane());
        root.setRight(createControlPane());

        updateImageView(originalView, sourceMat);
        applyProcessing();

        Scene scene = new Scene(root, 1550, 820);
        stage.setTitle("OpenCV Image Helper");
        stage.setScene(scene);
        stage.show();
    }

    private void replaceImage(Mat mat) {
        if (mat == null || mat.empty()) {
            return;
        }
        this.sourceMat = mat;
        sharedSourceMat = mat.clone();
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
        HBox hBox = new HBox(10, wrapWithTitledPane("Original", originalView), wrapWithTitledPane("Processed", processedView));
        hBox.setAlignment(Pos.CENTER);
        ScrollPane scrollPane = new ScrollPane(hBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        return scrollPane;
    }

    private TitledPane wrapWithTitledPane(String title, ImageView imageView) {
        BorderPane pane = new BorderPane(imageView);
        pane.setPrefSize(720, 720);
        TitledPane titledPane = new TitledPane(title, pane);
        titledPane.setCollapsible(false);
        return titledPane;
    }

    private ImageView createImageView() {
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(720);
        view.setFitHeight(720);
        return view;
    }

    private VBox createControlPane() {
        VBox container = new VBox(10);
        container.setPadding(new Insets(10));
        container.setPrefWidth(360);

        Button loadButton = new Button("Load Image...");
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setOnAction(evt -> {
            Mat loaded = requestImageFromUser(primaryStage);
            if (loaded != null && !loaded.empty()) {
                sourceMat = loaded;
                sharedSourceMat = loaded.clone();
                updateImageView(originalView, sourceMat);
                applyProcessing();
            }
        });

        Button resetButton = new Button("Reset to Original");
        resetButton.setMaxWidth(Double.MAX_VALUE);
        resetButton.setOnAction(evt -> {
            if (sharedSourceMat != null && !sharedSourceMat.empty()) {
                sourceMat = sharedSourceMat.clone();
                updateImageView(originalView, sourceMat);
                applyProcessing();
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
        removeUnderlineCheck = createToggle("Remove horizontal underlines", true, toggles);
        dilateCheck = createToggle("Apply dilation", true, toggles);
        erodeCheck = createToggle("Apply erosion", false, toggles);
        morphOpenCheck = createToggle("Morph open", false, toggles);
        morphCloseCheck = createToggle("Morph close", false, toggles);
        showContoursCheck = createToggle("Draw contours", true, toggles);

        container.getChildren().add(new TitledPane("Toggles", toggles));

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
                slider.setValue(Math.round(newVal.doubleValue()));
                valueLabel.setText(Long.toString(Math.round(newVal.doubleValue())));
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
        Mat processed = process(sourceMat.clone());
        updateImageView(processedView, processed);
        processed.release();
    }

    private Mat process(Mat input) {
        Mat working = input.clone();
        Mat gray = new Mat();
        if (working.channels() > 1) {
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = working.clone();
        }

        int blurKernel = (int) blurSlider.getValue();
        if (blurKernel % 2 == 0) {
            blurKernel++;
        }
        if (blurKernel > 1) {
            Imgproc.GaussianBlur(gray, gray, new Size(blurKernel, blurKernel), 0);
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
                    Imgproc.THRESH_BINARY_INV, blockSize, c);
        } else {
            double thresh = globalThresholdSlider.getValue();
            int mode = invertCheck.isSelected() ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY;
            Imgproc.threshold(gray, binary, thresh, 255, mode);
        }

        if (invertCheck.isSelected() && adaptiveCheck.isSelected()) {
            Core.bitwise_not(binary, binary);
        }

        if (removeUnderlineCheck.isSelected()) {
            binary = removeHorizontalArtifacts(binary,
                    (int) underlineLengthSlider.getValue(),
                    (int) underlineThicknessSlider.getValue(),
                    inpaintSpinner.getValue());
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

        if (showContoursCheck.isSelected()) {
            drawContours(binary, display, contourMinAreaSlider.getValue());
        }

        gray.release();
        binary.release();
        return display;
    }

    private Mat removeHorizontalArtifacts(Mat binary, int length, int thickness, int inpaintRadius) {
        Mat working = binary.clone();
        Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(Math.max(1, length), Math.max(1, thickness)));
        Mat detectedLines = new Mat();
        Imgproc.morphologyEx(working, detectedLines, Imgproc.MORPH_OPEN, horizontalKernel);

        Mat cleaned = new Mat();
        Core.subtract(working, detectedLines, cleaned);

        if (inpaintRadius > 0) {
            Mat mask = new Mat();
            Imgproc.threshold(detectedLines, mask, 0, 255, Imgproc.THRESH_BINARY);
            Imgproc.inpaint(cleaned, mask, cleaned, inpaintRadius, Imgproc.INPAINT_NS);
            mask.release();
        }

        detectedLines.release();
        working.release();
        return cleaned;
    }

    private void drawContours(Mat binary, Mat display, double minArea) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
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
        hierarchy.release();
        contourInfoLabel.setText("Contours: " + count + " (min area " + decimalFormat.format(minArea) + ")");
    }

    private void updateImageView(ImageView view, Mat mat) {
        Image image = matToImage(mat);
        view.setImage(image);
    }

    private Image matToImage(Mat mat) {
        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".png", mat, buffer);
        return new Image(new ByteArrayInputStream(buffer.toArray()));
    }
}
