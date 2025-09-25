# Extraction Pipeline Notes

This document explains how the extraction components in this package combine
OpenCV and Tesseract to convert PDF or image inputs into the form schema used by
the API. Every section calls out concrete implementations, the reasoning behind
key decisions, and alternatives you can experiment with.

## High-level flow

1. **Entry points** – `OpenCvOnlyExtractionStrategy` (`OpenCvOnlyExtractionStrategy.java:14-92`)
   handles image-based processing, and `PdfBoxOpenCvExtractionStrategy`
   (`PdfBoxOpenCvExtractionStrategy.java:30-166`) converts PDFs into images
   before delegating to OpenCV and OCR stages.
2. **Layout analysis** – `OpenCvLayoutAnalyzer` (`OpenCvLayoutAnalyzer.java:20-149`)
   performs contour detection on binarised images to infer rectangular regions
   representing fields, labels, groups, etc.
3. **Text extraction** – `TesseractOcrEngine` (`TesseractOcrEngine.java:29-205`)
   preprocesses the detected regions and runs Tess4J to recognise text when PDF
   text selection is unavailable or empty.
4. **Native bootstrap** – `NativeTesseractBootstrap`
   (`NativeTesseractBootstrap.java:12-90`) loads vetted native binaries supplied
   by the Bytedeco presets so the OCR engine works on macOS arm64 without
   copying dylibs into the repo.

`DetectionDiagnostics` (used in both strategies) reports components whose
confidence drops below 50 %, allowing you to iterate on thresholds quickly.

---

## OpenCV layout analysis

### Pre-processing (`OpenCvLayoutAnalyzer.java:82-96`)

1. **Grayscale conversion** – `Imgproc.cvtColor` projects the RGB page into a
   single channel to simplify later steps. This is the standard first step: it
   reduces noise from colour channels that rarely add value for printed forms.

2. **Gaussian blur (`Size(5, 5)`, sigma = 0)** – smoothing suppresses high
   frequency noise (e.g. scan artefacts) while preserving broad structures like
   table borders. The `5×5` kernel is a balance: smaller kernels (`3×3`) leave
   more noise; larger kernels (`9×9`) blur thin lines and can merge close
   components. Alternatives include:
   - **Bilateral filter** – better edge preservation but much slower on full
     pages. Useful if you notice borders being washed out.
   - **Median blur** – more aggressive on salt-and-pepper noise; can distort
     text edges and shrink thin rectangles.

3. **Adaptive thresholding** – `Imgproc.adaptiveThreshold` with
   `ADAPTIVE_THRESH_GAUSSIAN_C`, block size `35`, C `5`, and inverted binary
   output (`THRESH_BINARY_INV`). Reasons:
   - Gaussian mode handles non-uniform illumination common in scans.
   - Block size `35` works well around the form’s character sizes; reducing it
     (e.g. `15`) makes the threshold respond to individual glyph strokes and can
     fragment large regions; increasing (`51+`) risks merging adjacent elements.
   - Constant `C=5` offsets the local mean; lower values tighten thresholds and
     can lose faint lines, higher values (`10+`) create thicker blobs.
   - Inversion puts ink as white on black background, which works better for the
     subsequent morphological close and contour detection.
   Alternatives:
   - **Otsu threshold** (global) is faster but struggles with uneven lighting;
     you could swap to `Imgproc.threshold` with `THRESH_OTSU` if inputs are
     generated (non-scanned) PDFs.
   - **Sauvola or Niblack** (not in OpenCV core) give more control for
     handwriting; they require additional libraries.

4. **Morphological close (`MORPH_RECT 3×3`)** – fills small gaps in lines and
   merges adjacent pixels into continuous edges. Increasing kernel size connects
   wider gaps (useful for broken frames) but may merge unrelated elements; using
   `MORPH_OPEN` instead would remove small noise but could break thin borders.

### Contour filtering (`OpenCvLayoutAnalyzer.java:38-53`)

- **Contour extraction** – `Imgproc.findContours` with `RETR_TREE` keeps the
  hierarchy so nested boxes (e.g. checkbox inside a table cell) remain
  discoverable. `CHAIN_APPROX_SIMPLE` compresses horizontal/vertical points to
  save memory.
- **Minimum/maximum area ratios (`MIN_COMPONENT_AREA_RATIO = 0.0005`,
  `MAX_COMPONENT_AREA_RATIO = 0.8`)** filter out noise and page-sized blobs. The
  lower bound (~0.05 % of the page) rejects tiny specks; tightening it (e.g.
  `0.001`) reduces false positives but may drop small checkboxes. The upper
  bound removes the whole page or large background rectangles; lowering it
  catches headers but could discard large comment boxes.
- **Hard size check** – `rect.height <= 4 || rect.width <= 4` rejects extremely
  thin artefacts that slip through the area ratio.

### Component classification (`OpenCvLayoutAnalyzer.java:99-128`)

A heuristic maps rectangles to `DetectedComponentType` and, when relevant, to a
probable widget type. Decisions are driven by aspect ratios and dimensions:

- **Aspect ratio ≥ 8 & height < 80 → TEXT** – long thin boxes are assumed to be
  labels. Raising the aspect ratio threshold makes the classifier more selective
  (fewer mislabelled subheaders) but can misidentify multi-line labels. Lowering
  it catches more text regions but may mark square fields as text.
- **Width/height < 80 → FIELD** – small boxes are likely input fields. You can
  tie this to DPI: if you render at 300 DPI, increase the thresholds proportionally.
- **Height > width × 1.5 → GROUP** – tall rectangles usually denote grouping
  containers. Removing this rule reduces group-level detection but simplifies the
  output when you only care about atomic fields.

`inferWidgetType` adds a coarse widget guess:
- Aspect ratio < 1.2 → `CHECKBOX`.
- Height > 60 → `TEXTAREA` (captures comment boxes).
- Else → `TEXT` (single-line input).
Tune or replace these heuristics with a machine-learning classifier if you have
labelled data.

### Confidence metric (`OpenCvLayoutAnalyzer.java:130-139`)

The mean intensity of the ROI (after binarisation) becomes a normalized
confidence score. Bright (mostly white) regions imply clean separation, while
noisy patches produce lower scores. Alternatives include:
- **Edge density** (Canny + pixel ratio) to detect boxes with crisp borders.
- **Contour solidity** (area/convex hull) to penalise jagged contours.
- **Deep learning** – using a trained detector (e.g. Detectron2) would trump
  heuristics but requires dataset creation and increases runtime.

The current metric is cheap but simplistic; adjust or replace it if confidence
does not correlate with OCR accuracy in your samples.

### Logging (`OpenCvLayoutAnalyzer.java:68-78`)

Only group components are logged to reduce noise. This is useful when verifying
section detection. You can extend the stream to log other types or attach the
confidence scores for debugging.

---

## Image and PDF strategies

### `OpenCvOnlyExtractionStrategy`

- Loads fallback images from the classpath (`OpenCvOnlyExtractionStrategy.java:49-67`).
  This keeps development self-contained while waiting for PDF integration.
  Replace `imageLoader.loadClasspathImage` with file uploads if you need
  end-to-end ingestion.
- Runs layout analysis on each page, aggregates results, and invokes OCR when
  enabled (`OpenCvOnlyExtractionStrategy.java:79-91`).
- `DetectionDiagnostics` records sub-50 % confidence components and prints them
  once per request (`OpenCvOnlyExtractionStrategy.java:55-71`). Lower the
  threshold inside `DetectionDiagnostics` if you want to flag only truly
  problematic entries; raise it to surface more areas for manual review.

### `PdfBoxOpenCvExtractionStrategy`

- Renders each page at 200 DPI (`PdfBoxOpenCvExtractionStrategy.java:34`). This
  resolution balances detail vs. processing cost. Raising to 300 DPI improves
  OCR readability (more pixels per glyph) at the cost of longer render/OCR time
  and higher memory usage. Dropping to 150 DPI speeds things up but can blur
  fine text and narrow field borders.
- `PDFRenderer.renderImageWithDPI` with `ImageType.RGB` ensures colour
  consistency and avoids alpha channel overhead (`PdfBoxOpenCvExtractionStrategy.java:80`).
  Using `ImageType.GRAY` saves memory but may lose contrast for coloured labels.
- Region text extraction tries PDF text first via `PDFTextStripperByArea` and
  only falls back to OCR on empty/whitespace strings (`PdfBoxOpenCvExtractionStrategy.java:121-136`).
  This preserves original text quality when available. You can invert the check
  if you prefer OCR-only pipelines.
- The same detection diagnostics help triage uncertain components as in the
  OpenCV-only path.

---

## Tesseract OCR

### Native library loading (`NativeTesseractBootstrap.java:22-54`)

`Loader.load(leptonica.class)` and `Loader.load(tesseract.class)` extract the
macOS arm64 DLLs from the Bytedeco preset jars into the JavaCPP cache. We then
prepend the cache folder to `jna.library.path` and `java.library.path`, ensuring
Tess4J can resolve `libjnileptonica`/`libjnitesseract` without manual copying.

If you operate in a managed runtime (e.g. AWS Lambda) you can cache the extracted
files elsewhere by setting `org.bytedeco.javacpp.cachedir`. Alternatively, if you
have system-packaged libraries, call `System.setProperty("jna.library.path", ...)`
with your custom path and skip the loader entirely.

### OCR configuration (`TesseractOcrEngine.java:70-205`)

1. **ThreadLocal pooling** – `tesseractThreadLocal` gives every thread its own
   `ITesseract` instance (`TesseractOcrEngine.java:38`). Tesseract is not
   thread-safe; pooling avoids repeated initialization without sharing state.

2. **Engine configuration** – `setPageSegMode(6)` and `setOcrEngineMode(1)`
   (`TesseractOcrEngine.java:75-77`):
   - **PSM 6** assumes a uniform block of text, which matches cropped fields. If
     you process sparse checkboxes, try PSM `7` (single text line) or `11`
     (sparse text). Using PSM `3` (auto) increases resilience to complex layouts
     at the cost of slower orientation detection.
   - **OEM 1** (LSTM-only) gives the best accuracy on modern models. OEM `3`
     enables legacy + LSTM for compatibility with older traineddata but consumes
     more memory.
   - `preserve_interword_spaces=1` keeps multiple spaces; removing it allows
     Tesseract to collapse whitespace, which may be desirable when fields are
     strictly alphanumeric.

3. **Tessdata resolution** – `resolveTessDataPath` respects configuration in
   `application.properties` and unpacks classpath resources when needed
   (`TesseractOcrEngine.java:82-145`). This lets you ship custom traineddata in
   `src/main/resources/tessdata`. If you want to stick with Bytedeco’s bundled
   models, call `NativeTesseractBootstrap` earlier to set `TESSDATA_PREFIX`.

4. **Region pre-processing** (`TesseractOcrEngine.java:161-182`):
   - **Resize ×2** via `Imgproc.resize` with `INTER_LINEAR` improves OCR on small
     fonts by giving Tesseract more pixels per character. Raising the factor to
     `3.0` can help with very small text but increases processing time; dropping
     below `1.5` may reduce accuracy.
   - **Bilateral filter (radius 7, sigmaColor 60, sigmaSpace 60)** preserves
     edges (character strokes) while smoothing background noise. Alternative
     options:
     - *Median blur* handles salt-and-pepper noise but may distort strokes.
     - *Gaussian blur* is faster but softens edges; only use when input noise is
       minimal.
   - **Otsu threshold** (`THRESH_BINARY | THRESH_OTSU`) produces a clean
     black/white mask without tuning a constant. If you consistently see faint
     strokes disappearing, switch to `THRESH_BINARY_INV` or combine with
     adaptive thresholding.
   - **Morphological close (3×3)** removes small holes inside characters. Using a
     larger kernel (e.g. `5×5`) can join fractured text but risks thickening
     strokes. You can skip morphology entirely for high-quality scans.

5. **Failure handling** – OCR exceptions are caught and logged at `WARN`
   (`TesseractOcrEngine.java:147-158`). Adjust to `ERROR` if you need monitoring
   alerts when OCR fails, or throw to propagate back to clients.

### Alternative OCR strategies

- **Run Tess4J with hOCR output** – call `tesseractThreadLocal.get().createDocuments`
  if you need positional metadata beyond plain text.
- **Switch engines** – Google’s Cloud Vision or AWS Textract offer higher
  accuracy on handwriting but add network latency and cost.
- **Integrate language-specific models** – change `form.processing.ocr-language`
  and package additional traineddata; consider using FastText-based language
  detection before OCR for multilingual forms.

---

## Tuning suggestions

| Goal | Lever | Location | Effect |
| --- | --- | --- | --- |
| Detect smaller checkboxes | Lower `MIN_COMPONENT_AREA_RATIO` | `OpenCvLayoutAnalyzer.java:25` | Finds more small contours but increases noise; combine with higher minimum height check. |
| Reduce false positives on large containers | Lower `MAX_COMPONENT_AREA_RATIO` | `OpenCvLayoutAnalyzer.java:26` | Ignores oversized blobs; may hide genuine group boxes. |
| Speed up processing | Reduce `RENDER_DPI` to 150 | `PdfBoxOpenCvExtractionStrategy.java:34` | Faster rendering/OCR but can blur small fonts. |
| Improve OCR on faint prints | Increase resize factor to 2.5–3.0 | `TesseractOcrEngine.java:165` | Better accuracy but slower per region. |
| Suppress noise around text | Switch bilateral filter to median blur | `TesseractOcrEngine.java:168-169` | Removes impulsive noise quickly but may erode serif edges. |
| Get cleaner binaries | Replace adaptive threshold with Otsu | `OpenCvLayoutAnalyzer.java:88-90` | Simpler, faster, but sensitive to lighting variations. |

---

### Where to explore next

- **Learned detectors** – Replace heuristics with a convolutional neural network
  trained on annotated form layouts for better generalization to new templates.
- **Layout graph creation** – Currently `FormDocumentAssembler` creates flat
  nodes; consider analyzing contour hierarchy to build nested groups that more
  faithfully mirror the form structure.
- **Confidence calibration** – Capture labelled data to correlate the current
  mean-intensity score with actual OCR correctness, then adjust thresholds.

Use this document as a map when you need to tweak detection or OCR and to
understand the trade-offs inherent in each step.
