package com.fnma.rentrollpoc.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnma.rentrollpoc.models.ExtractionPayload;
import com.fnma.rentrollpoc.models.ExtractionPayload.BlockPayload;
import com.fnma.rentrollpoc.models.ExtractionPayload.BBox;
import com.fnma.rentrollpoc.models.ExtractionPayload.ColumnBand;
import com.fnma.rentrollpoc.models.ExtractionPayload.HeaderLine;
import com.fnma.rentrollpoc.models.ExtractionPayload.LinePayload;
import com.fnma.rentrollpoc.models.ExtractionPayload.PagePayload;
import com.fnma.rentrollpoc.models.ExtractionPayload.Size;
import com.fnma.rentrollpoc.models.ExtractionPayload.Source;
import com.fnma.rentrollpoc.models.ExtractionPayload.WordPayload;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.bytedeco.leptonica.presets.leptonica;
import org.bytedeco.tesseract.presets.tesseract;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.util.ImageHelper;
import nu.pattern.OpenCV;

@Service
public class PdfExtractService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final List<String> COLUMN_ORDER = List.of("Unit", "Tenant Name", "Unit Type", "Move-in Date",
            "Total Unit Rent", "Market Rent", "Subsidy Rent", "Lease Start Date", "Lease End Date");
    private static volatile String tessdataPrefix;
    private static final Map<String, String> HEADER_SYNONYMS = Map.ofEntries(
            Map.entry("unit type", "Unit Type"),
            Map.entry("unit", "Unit"),
            Map.entry("tenant", "Tenant Name"),
            Map.entry("name", "Tenant Name"),
            Map.entry("move in", "Move-in Date"),
            Map.entry("move-in", "Move-in Date"),
            Map.entry("move", "Move-in Date"),
            Map.entry("rent", "Market Rent"),
            Map.entry("market", "Market Rent"),
            Map.entry("hap", "Subsidy Rent"),
            Map.entry("subsidy", "Subsidy Rent"),
            Map.entry("lease start", "Lease Start Date"),
            Map.entry("start", "Lease Start Date"),
            Map.entry("lease end", "Lease End Date"),
            Map.entry("end", "Lease End Date"),
            Map.entry("total", "Total Unit Rent"),
            Map.entry("charges", "Total Unit Rent"));

    public Map<String, Object> extractToJsonPayload(MultipartFile pdf) throws Exception {
        Files.createDirectories(Path.of("src/main/resources/debug"));
        Files.createDirectories(Path.of("src/main/resources/output"));
        List<PagePayload> pages = new ArrayList<>();

        byte[] pdfBytes = pdf.getBytes();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, 400, ImageType.RGB);
                Mat source = bufferedToMat(rendered);
                Mat grayStrong = preprocessToGray(source);
                Mat grayLight = preprocessLight(source);
                List<OcrBox> blocks = new ArrayList<>();
                List<OcrBox> paras = new ArrayList<>();
                List<OcrBox> lines = new ArrayList<>();
                List<OcrBox> wordsStrong = new ArrayList<>();
                performOcr(rendered, grayStrong, blocks, paras, lines, wordsStrong);
                List<OcrBox> wordsLight = new ArrayList<>();
                performOcr(rendered, grayLight, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), wordsLight);
                List<OcrBox> numericWords = new ArrayList<>();
                performNumericOcr(rendered, grayLight, numericWords);
                List<OcrBox> mergedWords = mergeWords(wordsStrong, wordsLight);
                mergedWords = mergeWords(mergedWords, numericWords);
                saveDebug(rendered, blocks, paras, lines, mergedWords, pageIndex);
                pages.add(buildPagePayload(rendered, pageIndex, blocks, lines, mergedWords));
                source.release();
                grayStrong.release();
                grayLight.release();
            }
        }
        String ts = TS.format(LocalDateTime.now());
        Path jsonPath = Path.of("src/main/resources/output", "payload_" + ts + ".json");
        ExtractionPayload docPayload = new ExtractionPayload(
                ts,
                new Source(pdf.getOriginalFilename(), pages.size(), 400,
                        List.of("grayStrong(adaptiveThresh)", "grayLight", "numericPass"), "eng"),
                pages);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(jsonPath.toFile(), docPayload);
        Map<String, Object> result = new HashMap<>();
        result.put("payloadPath", jsonPath.toString());
        result.put("payload", docPayload);
        result.put("debugDir", "src/main/resources/debug");
        return result;
    }

    private Mat bufferedToMat(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        int[] data = img.getRGB(0, 0, width, height, null, 0, width);
        byte[] bytes = new byte[width * height * 3];
        for (int i = 0; i < data.length; i++) {
            int val = data[i];
            bytes[i * 3] = (byte) ((val >> 16) & 0xFF);
            bytes[i * 3 + 1] = (byte) ((val >> 8) & 0xFF);
            bytes[i * 3 + 2] = (byte) (val & 0xFF);
        }
        mat.put(0, 0, bytes);
        return mat;
    }

    private Mat preprocessToGray(Mat source) {
        Mat gray = new Mat();
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY);
        if (gray.type() != CvType.CV_8UC1) {
            Mat converted = new Mat();
            gray.convertTo(converted, CvType.CV_8UC1);
            gray.release();
            gray = converted;
        }
        Imgproc.GaussianBlur(gray, gray, new org.opencv.core.Size(3, 3), 0);
        Imgproc.adaptiveThreshold(gray, gray, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY, 15, 5);
        return gray;
    }

    private Mat preprocessLight(Mat source) {
        Mat gray = new Mat();
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY);
        if (gray.type() != CvType.CV_8UC1) {
            Mat converted = new Mat();
            gray.convertTo(converted, CvType.CV_8UC1);
            gray.release();
            gray = converted;
        }
        Imgproc.GaussianBlur(gray, gray, new org.opencv.core.Size(1, 1), 0);
        return gray;
    }

    private void performOcr(BufferedImage originalRgb, Mat grayMat, List<OcrBox> blocks, List<OcrBox> paras,
            List<OcrBox> lines, List<OcrBox> words) throws TesseractException {
        Tesseract tesseract = new Tesseract();
        if (tessdataPrefix != null) {
            tesseract.setDatapath(tessdataPrefix);
        }
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(6); // assume a block of text with columns
        BufferedImage grayImage = matToBufferedGray(grayMat);
        blocks.addAll(toBoxes(tesseract.getWords(grayImage, TessPageIteratorLevel.RIL_BLOCK)));
        paras.addAll(toBoxes(tesseract.getWords(grayImage, TessPageIteratorLevel.RIL_PARA)));
        lines.addAll(toBoxes(tesseract.getWords(grayImage, TessPageIteratorLevel.RIL_TEXTLINE)));
        words.addAll(toBoxes(tesseract.getWords(grayImage, TessPageIteratorLevel.RIL_WORD)));
    }

    private void performNumericOcr(BufferedImage originalRgb, Mat grayMat, List<OcrBox> words) throws TesseractException {
        Tesseract tesseract = new Tesseract();
        if (tessdataPrefix != null) {
            tesseract.setDatapath(tessdataPrefix);
        }
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(6);
        tesseract.setTessVariable("tessedit_char_whitelist", "0123456789.,-");
        BufferedImage grayImage = matToBufferedGray(grayMat);
        words.addAll(toBoxes(tesseract.getWords(grayImage, TessPageIteratorLevel.RIL_WORD)));
    }

    private List<OcrBox> mergeWords(List<OcrBox> primary, List<OcrBox> secondary) {
        List<OcrBox> merged = new ArrayList<>(primary);
        for (OcrBox w : secondary) {
            boolean dup = merged.stream().anyMatch(m -> overlap(m.box(), w.box()) > 0.5 && textSimilar(m.text(), w.text()));
            if (!dup) {
                merged.add(w);
            }
        }
        return merged;
    }

    private double overlap(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        int areaA = a.width * a.height;
        int areaB = b.width * b.height;
        int union = areaA + areaB - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private boolean textSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String sa = a.trim().toLowerCase(Locale.ROOT);
        String sb = b.trim().toLowerCase(Locale.ROOT);
        if (sa.equals(sb)) return true;
        return Math.abs(sa.length() - sb.length()) <= 1 && (sa.contains(sb) || sb.contains(sa));
    }

    private List<OcrBox> toBoxes(List<Word> input) {
        List<OcrBox> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        for (Word w : input) {
            if (w == null || w.getBoundingBox() == null) {
                continue;
            }
            Rect r = new Rect(w.getBoundingBox().x, w.getBoundingBox().y, w.getBoundingBox().width,
                    w.getBoundingBox().height);
            out.add(new OcrBox(w.getText(), r));
        }
        return out;
    }

    private BufferedImage matToBufferedGray(Mat mat) {
        int width = mat.width();
        int height = mat.height();
        byte[] data = new byte[width * height];
        mat.get(0, 0, data);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setDataElements(0, 0, width, height, data);
        return ImageHelper.convertImageToGrayscale(image);
    }

    private void saveDebug(BufferedImage original, List<OcrBox> blocks, List<OcrBox> paras, List<OcrBox> lines,
            List<OcrBox> words, int pageIndex) throws Exception {
        Mat debugMat = bufferedToMat(original);
        if (debugMat.type() != CvType.CV_8UC3) {
            Mat converted = new Mat();
            Imgproc.cvtColor(debugMat, converted, Imgproc.COLOR_RGB2BGR);
            debugMat.release();
            debugMat = converted;
        }
        drawBoxes(debugMat, blocks, new Scalar(255, 0, 0));
        drawBoxes(debugMat, paras, new Scalar(255, 165, 0));
        drawBoxes(debugMat, lines, new Scalar(0, 255, 0));
        drawBoxes(debugMat, words, new Scalar(0, 0, 255));
        Path outPath = Path.of("src/main/resources/debug", "page_" + (pageIndex + 1) + ".png");
        Imgcodecs.imwrite(outPath.toString(), debugMat);
        debugMat.release();
    }

    private void drawBoxes(Mat mat, List<OcrBox> boxes, Scalar color) {
        for (OcrBox b : boxes) {
            Rect r = b.box();
            Imgproc.rectangle(mat, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height), color, 2);
        }
    }

    private List<List<OcrBox>> clusterByLine(List<OcrBox> words) {
        List<OcrBox> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(b -> b.box().y));
        List<List<OcrBox>> lines = new ArrayList<>();
        int tolerance = 12;
        for (OcrBox w : sorted) {
            boolean placed = false;
            for (List<OcrBox> line : lines) {
                int y = line.get(0).box().y;
                if (Math.abs(w.box().y - y) <= tolerance) {
                    line.add(w);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<OcrBox> newLine = new ArrayList<>();
                newLine.add(w);
                lines.add(newLine);
            }
        }
        for (List<OcrBox> line : lines) {
            line.sort(Comparator.comparingInt(b -> b.box().x));
        }
        return lines;
    }

    private Optional<List<OcrBox>> findHeader(List<List<OcrBox>> lines) {
        int bestScore = -1;
        List<OcrBox> best = null;
        for (List<OcrBox> line : lines) {
            HashSet<String> matched = new HashSet<>();
            for (OcrBox w : line) {
                String t = w.text().toLowerCase(Locale.ROOT);
                for (String key : HEADER_SYNONYMS.keySet()) {
                    if (t.contains(key)) {
                        matched.add(HEADER_SYNONYMS.get(key));
                        break;
                    }
                }
            }
            int score = matched.size();
            if (score > bestScore) {
                bestScore = score;
                best = line;
            } else if (score == bestScore && best != null) {
                // prefer line closer to data: lower y
                int currentY = line.get(0).box().y;
                int bestY = best.get(0).box().y;
                if (currentY > bestY) {
                    best = line;
                }
            }
        }
        return bestScore > 0 ? Optional.of(best) : Optional.empty();
    }

    private List<ColumnBoundary> buildBoundaries(List<OcrBox> header) {
        List<ColumnBoundary> boundaries = new ArrayList<>();
        List<OcrBox> sorted = new ArrayList<>(header);
        sorted.sort(Comparator.comparingInt(b -> b.box().x));
        List<Integer> centers = sorted.stream().map(b -> b.box().x + b.box().width / 2).toList();
        int medianGap = 0;
        if (centers.size() > 1) {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < centers.size(); i++) gaps.add(centers.get(i) - centers.get(i - 1));
            gaps.sort(Integer::compareTo);
            medianGap = gaps.get(gaps.size() / 2);
        }
        int prevEnd = 0;
        for (int i = 0; i < sorted.size(); i++) {
            OcrBox box = sorted.get(i);
            int center = box.box().x + box.box().width / 2;
            int pad = medianGap > 0 ? medianGap / 2 : box.box().width;
            int start = i == 0 ? Math.max(0, center - pad) : (prevEnd + center) / 2;
            int end;
            if (i < sorted.size() - 1) {
                int nextCenter = sorted.get(i + 1).box().x + sorted.get(i + 1).box().width / 2;
                end = (center + nextCenter) / 2;
            } else {
                end = center + pad;
            }
            String colName = mapHeader(box.text());
            // tighten narrow column for HAP/Subsidy
            if ("Subsidy Rent".equals(colName) && medianGap > 0) {
                int mid = (start + end) / 2;
                int narrow = medianGap / 2;
                start = mid - narrow / 2;
                end = mid + narrow / 2;
            }
            boundaries.add(new ColumnBoundary(colName, start, end));
            prevEnd = end;
        }
        boundaries.sort(Comparator.comparingInt(ColumnBoundary::start));
        for (String col : COLUMN_ORDER) {
            boolean exists = boundaries.stream().anyMatch(b -> b.name().equals(col));
            if (!exists) {
                int lastEnd = boundaries.isEmpty() ? 0 : boundaries.get(boundaries.size() - 1).end();
                boundaries.add(new ColumnBoundary(col, lastEnd, lastEnd + (medianGap > 0 ? medianGap : 200)));
            }
        }
        boundaries.sort(Comparator.comparingInt(ColumnBoundary::start));
        return boundaries;
    }

    private String mapHeader(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("unit type")) return "Unit Type";
        if (t.contains("tenant")) return "Tenant Name";
        if (t.contains("move")) return "Move-in Date";
        if (t.contains("total")) return "Total Unit Rent";
        if (t.contains("market")) return "Market Rent";
        if (t.contains("hap") || t.contains("subsid")) return "Subsidy Rent";
        if (t.contains("lease start")) return "Lease Start Date";
        if (t.contains("lease end")) return "Lease End Date";
        if (t.contains("unit")) return "Unit";
        return text.trim();
    }

    private Map<String, String> assignColumns(List<OcrBox> line, List<ColumnBoundary> boundaries) {
        Map<String, StringBuilder> accum = new HashMap<>();
        List<Integer> centers = boundaries.stream().map(b -> (b.start() + b.end()) / 2).toList();
        int medianGap = 0;
        if (centers.size() > 1) {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < centers.size(); i++) gaps.add(centers.get(i) - centers.get(i - 1));
            gaps.sort(Integer::compareTo);
            medianGap = gaps.get(gaps.size() / 2);
        }
        for (OcrBox word : line) {
            int center = word.box().x + word.box().width / 2;
            ColumnBoundary target = boundaries.stream().filter(b -> center >= b.start() && center <= b.end()).findFirst().orElse(null);
            if (target == null) {
                // snap to nearest column center if outside ranges
                target = boundaries.get(0);
                int bestDist = Math.abs(center - centers.get(0));
                for (int i = 1; i < boundaries.size(); i++) {
                    int dist = Math.abs(center - centers.get(i));
                    if (dist < bestDist) {
                        bestDist = dist;
                        target = boundaries.get(i);
                    }
                }
                if (medianGap > 0 && bestDist > medianGap * 1.2) {
                    continue; // ignore outlier tokens
                }
            }
            accum.computeIfAbsent(target.name(), k -> new StringBuilder()).append(word.text()).append(" ");
        }
        Map<String, String> row = new HashMap<>();
        for (String col : COLUMN_ORDER) {
            String val = Optional.ofNullable(accum.get(col)).map(sb -> sb.toString().trim()).orElse("");
            row.put(col, val);
        }
        boolean hasTenant = row.values().stream().filter(Objects::nonNull).anyMatch(s -> !s.isBlank());
        if (!hasTenant) {
            return Map.of();
        }
        return row;
    }

    private double norm(int val, int total) {
        return total == 0 ? 0.0 : val / (double) total;
    }

    private BBox toBBox(Rect r) {
        return new BBox(r.x, r.y, r.width, r.height);
    }

    private BBox toBBoxNorm(Rect r, int width, int height) {
        return new BBox(norm(r.x, width), norm(r.y, height), norm(r.width, width), norm(r.height, height));
    }

    private boolean isNumeric(String text) {
        return text != null && text.matches("[\\d,\\.\\-]+");
    }

    private boolean isDate(String text) {
        return text != null && text.matches(".*\\d{2}[/\\-]\\d{2}[/\\-]\\d{2,4}.*");
    }

    private boolean isCurrency(String text) {
        return text != null && text.matches(".*\\d{1,3}(,\\d{3})*(\\.\\d{2})?.*");
    }

    private PagePayload buildPagePayload(BufferedImage rendered, int pageIndex, List<OcrBox> blocks, List<OcrBox> lines,
                                         List<OcrBox> words) {
        int width = rendered.getWidth();
        int height = rendered.getHeight();
        List<List<OcrBox>> clusteredLines = clusterByLine(words);
        Optional<List<OcrBox>> headerOpt = findHeader(clusteredLines);
        List<ColumnBoundary> bands = headerOpt.map(this::buildBoundaries).orElseGet(ArrayList::new);
        List<WordPayload> wordPayloads = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            OcrBox w = words.get(i);
            wordPayloads.add(new WordPayload("w" + i, w.text(),
                    toBBox(w.box()), toBBoxNorm(w.box(), width, height),
                    isNumeric(w.text()), isDate(w.text()), isCurrency(w.text())));
        }
        List<LinePayload> linePayloads = new ArrayList<>();
        int lineId = 0;
        for (List<OcrBox> ln : clusteredLines) {
            linePayloads.add(new LinePayload("l" + lineId++, ln.stream().map(OcrBox::text).collect(Collectors.joining(" ")),
                    toBBox(ln.get(0).box()), toBBoxNorm(ln.get(0).box(), width, height),
                    ln.stream().map(x -> "w" + words.indexOf(x)).toList()));
        }
        List<BlockPayload> blockPayloads = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            blockPayloads.add(new BlockPayload("b" + i,
                    toBBox(blocks.get(i).box()), toBBoxNorm(blocks.get(i).box(), width, height),
                    "TEXT", 0.8, List.of()));
        }
        HeaderLine headerLine = null;
        if (headerOpt.isPresent()) {
            List<OcrBox> h = headerOpt.get();
            List<String> matched = new ArrayList<>();
            for (OcrBox w : h) {
                String t = w.text().toLowerCase(Locale.ROOT);
                for (String key : HEADER_SYNONYMS.keySet()) {
                    if (t.contains(key)) {
                        matched.add(HEADER_SYNONYMS.get(key));
                        break;
                    }
                }
            }
            headerLine = new HeaderLine("l" + clusteredLines.indexOf(h),
                    h.stream().map(x -> "w" + words.indexOf(x)).toList(), matched, matched.size());
        }
        List<ColumnBand> columnBands = new ArrayList<>();
        for (ColumnBoundary b : bands) {
            double xs = b.start / (double) width;
            double xe = b.end / (double) width;
            columnBands.add(new ColumnBand(b.name, b.start, b.end, xs, xe));
        }
        return new PagePayload(pageIndex,
                new Size(width, height, 400, 1.0, 1.0),
                "UNKNOWN",
                headerLine,
                columnBands,
                blockPayloads,
                linePayloads,
                wordPayloads,
                Map.of("steps", List.of("ocr_strong", "ocr_light", "ocr_numeric")));
    }

    private record OcrBox(String text, Rect box) {
    }

    private record ColumnBoundary(String name, int start, int end) {
    }

    static {
        try {
            OpenCV.loadLocally();
        } catch (UnsatisfiedLinkError ignored) {
            try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            } catch (UnsatisfiedLinkError ignored2) {
                // ignore
            }
        }
        initTesseractNatives();
    }

    private static void initTesseractNatives() {
        try {
            System.setProperty("jna.nosys", "true");

            // Prefer bundled resources
            Path resourceLibDir = Path.of("src/main/resources/native/tesseract").toAbsolutePath();
            List<String> libPaths = new ArrayList<>();
            if (Files.isDirectory(resourceLibDir)) {
                libPaths.add(resourceLibDir.toString());
                Path resTessdata = resourceLibDir.resolve("tessdata");
                if (Files.isDirectory(resTessdata)) {
                    tessdataPrefix = resTessdata.toString();
                    System.setProperty("TESSDATA_PREFIX", tessdataPrefix);
                }
            }

            File leptLibrary = new File(org.bytedeco.javacpp.Loader.load(leptonica.class));
            File tessLibrary = new File(org.bytedeco.javacpp.Loader.load(tesseract.class));
            libPaths.add(leptLibrary.getParent());
            libPaths.add(tessLibrary.getParent());
            String merged = String.join(File.pathSeparator, libPaths);
            System.setProperty("jna.library.path", merged);
            System.setProperty("java.library.path", merged);
            if (System.getProperty("TESSDATA_PREFIX") == null) {
                File base = tessLibrary.getParentFile() != null ? tessLibrary.getParentFile().getParentFile() : null;
                if (base != null) {
                    File shareTessdata = new File(base, "share/tessdata");
                    if (shareTessdata.isDirectory()) {
                        tessdataPrefix = shareTessdata.getAbsolutePath();
                        System.setProperty("TESSDATA_PREFIX", tessdataPrefix);
                    }
                }
            }
            if (tessdataPrefix == null) {
                File resTessdata = Path.of("src/main/resources/native/tesseract/tessdata").toFile();
                if (resTessdata.isDirectory()) {
                    tessdataPrefix = resTessdata.getAbsolutePath();
                    System.setProperty("TESSDATA_PREFIX", tessdataPrefix);
                }
            }
        } catch (UnsatisfiedLinkError | RuntimeException ex) {
            throw new IllegalStateException("Failed to load Tesseract natives", ex);
        }
    }

}
