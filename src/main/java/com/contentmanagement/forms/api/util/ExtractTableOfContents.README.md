# ExtractTableOfContents Logic Notes

## Overview
The `ExtractTableOfContents` class reconstructs a logical grid from the table-border image (`tableLinesMat`) and aligns content contours from the full page (`contentMat`) into that grid. The main workflow:

1. **Line clustering** – Horizontal and vertical contours are grouped into `LineCluster`s and converted into ordered row/column boundaries.
2. **Cell fusion** – Adjacent slots with missing separators are unified via Union-Find to capture merged cells that span multiple rows or columns.
3. **Row slicing** – Each boundary interval becomes a `RowSlice` that will hold fragments, overrides, and checkbox observations for that logical row.
4. **Content assignment** – For each content contour from the full page, the code: (a) filters by table bounds, (b) routes the contour to a row/column slot using centroid lookup, (c) detects checkboxes by geometry + ink ratio, and (d) OCRs text fragments via the injected `OCREngine`.
5. **Column semantics** – Header text (first two rows) guides the mapping of physical columns to semantic roles (check included, tab, document, form number, delivery requirement). When header OCR is empty, the fallback is left-to-right priority.
6. **Propagation** – For span cells, text in the Tab/Document/Delivery columns is propagated downward so that each logical row receives the text that visually spans it, while Form Number remains unique per row.
7. **Row assembly** – Header-like rows are skipped, and the remaining slices become `RowRecord` objects containing only textual/boolean fields.

## Key Assumptions
- Line contours include every true cell border; semi-structured stretches without inner lines must still leave enough separating edges to compute discrete row boundaries.
- Each content contour’s centroid falls within the intended cell. (Large text blobs that overlap boundaries may register against the wrong row.)
- OCR for header rows returns enough keywords to map columns correctly; otherwise the fallback assumes the canonical column ordering left-to-right.
- Checkboxes appear as roughly square contours whose ink ratio after Otsu thresholding exceeds `CHECKBOX_INK_RATIO`.
- Multi-row blocks (e.g., Document descriptions) share a single merged cell that covers all participating row intervals; the union-find merging relies on missing lines between those rows/columns.

## Why All Rows Might Collapse Into One
If the reconstruction merges every row into a single cell, the likely causes are:
- **Horizontal separators not detected** – either line contours for the row dividers are absent or too fragmented, so coverage never exceeds the `COVERAGE_THRESHOLD` (0.55). In this case the union-find unifies the entire column stack. Lowering the threshold or ensuring contours cover most of each row span may resolve it.
- **Row boundary clustering too coarse** – `LINE_CLUSTER_EPS` defines how close lines must be to be treated as the same boundary. If multiple horizontal lines are nearly coincident (or the image is warped), the clustering might collapse them into one boundary. Calibrate `LINE_CLUSTER_EPS` with your contour measurements.
- **Contours trimmed outside table bounds** – if the bounding box clipping removes top/bottom lines, the outer boundaries can shift and swallow interior rows.
- **Content centroids misrouted** – very tall text contours (covering multiple physical rows) may report a single row; consider splitting these contours earlier or applying projection-based row alignment.

## Suggested Diagnostics
- Dump the computed `rowBoundaries` and `colBoundaries` arrays and check their lengths; they should reflect the expected grid.
- After `buildCellGrid`, log each `Cell`’s row/column span to confirm merges.
- Inspect the coverage returned by `hasHorizontal()` for representative cells. If coverage ratios are <0.55 despite lines being present, tweak `COVERAGE_THRESHOLD` or improve line contour extraction.
- Visualize the detected cell bounds by drawing rectangles on a debug image (using `cell.bounds`).

## Next Steps
Adjust thresholds or preprocessing so that each physical row has at least one separating line contour with sufficient span. Once the geometry is correct, the `extract` method should return one `RowRecord` per logical row instead of one giant merged record.
