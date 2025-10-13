# ExtractTableOfContents Algorithm Overview

This document explains the end-to-end algorithm implemented in `ExtractTableOfContents.java`. It walks through the major stages, shows pseudocode that mirrors the actual method calls, and outlines time/space complexity considerations. All method names referenced below exist inside `ExtractTableOfContents`.

## High-Level Flow (Pseudocode)

```
List<RowRecord> extract(linesMat, lineContours, contentMat, contentContours):
    grid ← TableGrid.fromContours(lineContours)
    if grid.isEmpty():
        return []

    gray ← toGray(contentMat)
    assignContent(grid, contentMat, gray, contentContours)
    gray.release()

    slices ← ColumnSlices.build(grid)
    records ← buildRecords(grid, slices)
    return records
```

Key stages referenced in the pseudocode:

1. `TableGrid.fromContours(...)` builds a normalized lattice of table cells using the supplied cell contours.
2. `assignContent(...)` performs OCR on each content contour and stores checkboxes/text inside the owning cell.
3. `ColumnSlices.build(...)` converts each column into an ordered list (stack) of `ContentSlice` / `CheckboxSlice` objects.
4. `buildRecords(...)` traverses those stacks to emit ordered `RowRecord` instances using the rules discussed below.

### Pseudocode for `TableGrid.fromContours`
```
fromContours(cellContours):
    rects ← bounding boxes of contours
    rowEdges ← clusterEdges(all top/bottom values)
    colEdges ← clusterEdges(all left/right values)
    matrix[row][col] ← new Cell spanning rect region (rowStart,rowEnd,colStart,colEnd)
    rows[] ← RowData objects for each logical row
    return TableGrid(rowEdges, colEdges, bounds, matrix, rows)
```
- Clustering collapses near-duplicate borders so merged cells are detected.
- Every `Cell` holds the geometrical bounds plus OCR fragments/checkbox state collected later.

### Pseudocode for `assignContent`
```
assignContent(grid, contentMat, gray, contentContours):
    for each contour:
        rect ← bounding box
        if rect outside grid bounds: continue
        (rowIndex, colIndex) ← locateInterval(center)
        columnType ← enum by column index (CHECK/TAB/DOCUMENT/FORM/DELIVERY)
        cell ← grid.cellAt(rowIndex, columnType)
        if columnType == CHECK and isCheckboxCandidate(rect):
            checked ← isCheckboxMarked(gray, rect)
            cell.addCheckbox(rect, checked)
            continue
        text ← performOcr(contentMat, rect)   (logs for TAB column)
        if text blank: continue
        cell.addFragment(rect, text)
```
- Each cell stores text fragments sorted later by vertical position.
- Checkbox detection is column-aware; only the `CHECK_INCLUDED` column tests boxes.

### Pseudocode for `ColumnSlices.build`
```
build(grid):
    tabSlices ← collectTextSlices(grid, TAB)
    documentSlices ← collectTextSlices(grid, DOCUMENT)
    formSlices ← collectTextSlices(grid, FORM_NUMBER)
    deliverySlices ← collectTextSlices(grid, DELIVERY_REQUIREMENT)
    checkSlices ← collectCheckboxSlices(grid)
    return ColumnSlices(tabSlices, documentSlices, formSlices, deliverySlices, checkSlices)
```
- `collectTextSlices` splits a cell’s fragment list into vertical slices (top, bottom, text), sorted by top coordinate.
- `collectCheckboxSlices` does the same for checkbox fragments.
- Each list acts as a stack (top-to-bottom order) for the row-building pass.

### Pseudocode for `buildRecords`
```
buildRecords(grid, slices):
    indices ← {tab=0, doc=0, form=0, delivery=0, check=0}
    activeTab ← tabSlices[0] if available
    ordered ← []

    while docIndex < documentSlices.size():
        docSlice ← documentSlices[docIndex]
        if docSlice.text blank: advance docIndex and continue

        banner ← classifyBanner(docSlice, grid)
        if banner != NONE:
            ordered.add(RowRecord(header/section row))
            docIndex++
            continue

        // align tab
        activeTab ← advanceTabIfNeeded(activeTab, tabSlices, docSlice)
        tabText ← activeTab.text if rangesOverlap(activeTab.cell, docSlice)

        // align checkbox
        matchedCheck ← advanceCheckboxUntilOverlap(checkSlices, docSlice)
        included ← matchedCheck != null and matchedCheck.checked

        // align form & delivery content
        formSlice ← consumeContentWithin(formSlices, formIndex, docTop, docBottom)
        deliverySlice ← consumeContentWithin(deliverySlices, deliveryIndex, docTop, docBottom)

        record ← RowRecord(included, tabText, docSlice.text,
                           formSlice?.text, deliverySlice?.text, false, false)
        ordered.add(record with visualTop)
        docIndex++

    sort ordered by visualTop
    return records
```
- Tab slices remain active until a document slice falls outside the tab cell’s vertical span.
- Checkbox slices advance when their center enters the document slice.
- Form and Delivery slices are consumed only when their vertical span is mostly inside the document slice (≥65% overlap or fully contained).
- Section/header rows (full-width uppercase spans) are emitted as single-row banners.

## Complexity Discussion

Let `n` be the number of table cell contours and `m` the number of content contours (text + checkbox).

1. **Grid construction (`TableGrid.fromContours`)**
   - Clustering edges: `O(n log n)` from sorting top/bottom/left/right coordinates.
   - Creating cell matrix: worst-case `O(n)` cells, each mapped to up to `rowCount × colCount` entries (bounded by grid size). For a typical form this is constant-sized.
   - Overall: `O(n log n)` time, `O(n)` space for descriptive cell objects.

2. **Content assignment (`assignContent`)**
   - For each contour we compute bounding boxes, locate intervals (`log rowCount` via linear scan, but rowCount is small), and optionally OCR. Assume OCR dominates but number of localized OCR calls equals number of contours `m`. Pure algorithmic cost is `O(m)`; OCR cost depends on tess engine.
   - Additional space `O(m)` for fragment metadata stored in cells.

3. **Slice construction (`ColumnSlices.build`)**
   - Collecting slices visits each fragment/checkbox once; sorting per cell is `O(k log k)` where `k` is fragments in that cell. Over the whole table this sums to `O(m log m)` worst case, but typical cells have few fragments (small constant).

4. **Record assembly (`buildRecords`)**
   - Main loop steps through each document slice once, and each supporting slice advances monotonically. This is a linear multi-pointer sweep: `O(m)` time, `O(1)` extra space besides the output list.
   - Final sort of `ordered` runs in `O(r log r)` where `r` is the number of emitted rows (bounded by document slices).

5. **Printing (`printRecordsAsTable`)** operates in `O(r × c × L)` where `c=7` columns and `L` is max wrapped line length; effectively linear in output size.

### Overall
- **Time complexity:** `O(n log n + m log m + r log r + OCR_cost)`; with modest table sizes, the dominant cost is OCR plus the initial contour sorting.
- **Space complexity:** `O(n + m + r)`, primarily for storing cells, fragments, slices, and output records.

## Function Summaries

### `TableGrid.fromContours`
- Normalizes the table geometry from cell contours, clusters near-duplicate edges, and builds a 2D cell matrix plus per-row metadata.

### `assignContent`
- Iterates over every content contour, assigns it to a cell and column, differentiates between checkboxes and textual content, and runs OCR on localized regions (logging each Tab attempt).

### `ColumnSlices.build`
- Converts per-cell fragments into ordered `ContentSlice` lists for each logical column, giving us stack-like structures ready for the sweep.

### `buildRecords`
- Drives the multi-pointer sweep: document slices form the backbone; tab, form, delivery, and checkbox slices advance as needed to emit one `RowRecord` per actual row; handles section/header banners specially and sorts the final list by vertical position.

### `printRecordsAsTable`
- Provides a console-friendly visualization of the output, wrapping columns to a fixed width and displaying banner rows as single full-width lines.

This overview should give you a thorough mental model of how the extractor operates and where to instrument or adjust if further refinements are needed.
