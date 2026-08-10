package com.speedread.rsvp.data.parser

/**
 * Pure page-geometry analysis that locates figure regions (diagrams, plates, illustrations)
 * on a source page. No PDFBox types — inputs are plain boxes so the logic is unit-testable
 * with synthetic layouts.
 *
 * Model: body prose forms full-width text lines at a regular vertical rhythm. A figure
 * manifests as a vertical BAND between body lines (or at a page edge, or spanning a page
 * with no body text at all) that contains either an embedded raster image or a cluster of
 * sparse text lines — the stray diagram labels that text extraction otherwise weaves into
 * the prose. A band with a single sparse line is page furniture (running header, page
 * number), not a figure, and is left alone.
 *
 * All coordinates are top-left-origin page points. Output boxes are normalized to 0..1 of
 * the page so they can be re-rendered from the original PDF at any scale. Word offsets are
 * page-relative counts in reading order, matching the import-time page word counting.
 */
object FigureRegionDetector {

    /**
     * One text line in reading order. [wordCount] uses the import tokenization. [gibberish]
     * marks lines the OCR layer mangled (see [isGibberish]) — they can never be body text,
     * however wide, because scanned plates emit page-wide garbage lines. [numericOnly] marks
     * bare page-number lines (see [isBareNumber]) — page furniture, never label evidence.
     */
    class TextLine(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val wordCount: Int,
        val gibberish: Boolean = false,
        val numericOnly: Boolean = false
    ) {
        val width: Float get() = right - left
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** Bounding box of an embedded raster image, top-left origin page points. */
    class ImageBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * A detected figure. Box fields are normalized (0..1, top-left). [anchorWordOffset] is
     * the number of page words preceding the figure in reading order (0 = page start).
     * [labelWordStart]/[labelWordEnd] are page-relative word ordinals of the region's stray
     * label tokens, inclusive, or -1/-1 when the region contributed no tokens.
     */
    class DetectedFigure(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val aspectRatio: Float,
        val anchorWordOffset: Int,
        val labelWordStart: Int,
        val labelWordEnd: Int
    )

    private class Band(val top: Float, val bottom: Float)

    fun detect(
        pageWidth: Float,
        pageHeight: Float,
        lines: List<TextLine>,
        images: List<ImageBox>
    ): List<DetectedFigure> {
        if (pageWidth <= 0f || pageHeight <= 0f) return emptyList()
        if (lines.isEmpty() && images.isEmpty()) return emptyList()

        val maxLineWidth = lines.maxOfOrNull { it.width } ?: 0f
        val bodyWidthFloor = maxOf(
            FigureDetectionConstants.BODY_LINE_MIN_WIDTH_FRACTION * maxLineWidth,
            FigureDetectionConstants.BODY_LINE_MIN_PAGE_WIDTH_FRACTION * pageWidth
        )
        val isBody = lines.map { line ->
            maxLineWidth > 0f &&
                !line.gibberish &&
                line.width >= bodyWidthFloor &&
                line.wordCount >= FigureDetectionConstants.BODY_LINE_MIN_WORDS
        }
        val bodyLines = lines.filterIndexed { i, _ -> isBody[i] }.sortedBy { it.top }

        // Scanned books wrap every page in one near-page-sized raster (the scan itself);
        // such an image is only figure evidence on a true plate page — no body text AND the
        // OCR layer is either absent or mangled. A chapter opener / title page also has no
        // body text but carries a couple of CLEAN heading lines; its page scan must not
        // turn the whole page into a figure. Smaller embedded images always count.
        val pageArea = pageWidth * pageHeight
        val allowPageScanImage = bodyLines.isEmpty() &&
            (lines.isEmpty() || lines.any { it.gibberish })
        val usableImages = images.filter { image ->
            val areaFraction = ((image.right - image.left) * (image.bottom - image.top)) / pageArea
            areaFraction <= FigureDetectionConstants.IMAGE_MAX_PAGE_AREA_FRACTION || allowPageScanImage
        }

        // Sparse lines typographically continuous with a body block (short paragraph tails,
        // footnote remnants above the page number) are prose, not diagram labels.
        val adjacencyThreshold = maxOf(
            FigureDetectionConstants.LABEL_BODY_ADJACENCY_GAP_FACTOR * medianBodyReference(bodyLines),
            FigureDetectionConstants.LABEL_BODY_ADJACENCY_PAGE_FRACTION * pageHeight
        )
        val bodyAdjacent = BooleanArray(lines.size) { i ->
            !isBody[i] && bodyLines.any { body ->
                val gap = maxOf(body.top - lines[i].bottom, lines[i].top - body.bottom)
                gap <= adjacencyThreshold
            }
        }

        val bands = findCandidateBands(pageHeight, bodyLines)
        if (bands.isEmpty()) return emptyList()

        // Prefix word counts over lines in reading order: cumWords[i] = words before line i.
        val cumWords = IntArray(lines.size + 1)
        for (i in lines.indices) cumWords[i + 1] = cumWords[i] + lines[i].wordCount

        val hasBody = bodyLines.isNotEmpty()
        val figures = mutableListOf<DetectedFigure>()
        for (band in bands) {
            val figure = evaluateBand(
                band, pageWidth, pageHeight, lines, isBody, bodyAdjacent, hasBody, cumWords, usableImages
            )
            if (figure != null) figures.add(figure)
        }

        return if (figures.size <= FigureDetectionConstants.MAX_REGIONS_PER_PAGE) {
            figures
        } else {
            // Keep the largest regions when a page over-triggers, restoring reading order.
            figures.sortedByDescending { it.width * it.height }
                .take(FigureDetectionConstants.MAX_REGIONS_PER_PAGE)
                .sortedBy { it.top }
        }
    }

    /**
     * Vertical intervals that could hold a figure: unusually large gaps between consecutive
     * body lines, the margins above the first / below the last body line, or the whole page
     * when no body text exists. Margin bands still pass through [evaluateBand]'s content
     * qualification, so ordinary header/footer margins never become figures.
     */
    private fun findCandidateBands(pageHeight: Float, bodyLines: List<TextLine>): List<Band> {
        val minBandHeight = FigureDetectionConstants.MIN_BAND_HEIGHT_FRACTION * pageHeight
        if (bodyLines.isEmpty()) return listOf(Band(0f, pageHeight))

        val bands = mutableListOf<Band>()
        val leading = Band(0f, bodyLines.first().top)
        if (leading.bottom - leading.top > minBandHeight) bands.add(leading)

        val medianReference = medianBodyReference(bodyLines)
        for (i in 0 until bodyLines.size - 1) {
            val gapTop = bodyLines[i].bottom
            val gapBottom = bodyLines[i + 1].top
            val gap = gapBottom - gapTop
            if (gap > minBandHeight && gap > FigureDetectionConstants.GAP_FACTOR * medianReference) {
                bands.add(Band(gapTop, gapBottom))
            }
        }

        val trailing = Band(bodyLines.last().bottom, pageHeight)
        if (trailing.bottom - trailing.top > minBandHeight) bands.add(trailing)
        return bands
    }

    private fun evaluateBand(
        band: Band,
        pageWidth: Float,
        pageHeight: Float,
        lines: List<TextLine>,
        isBody: List<Boolean>,
        bodyAdjacent: BooleanArray,
        hasBody: Boolean,
        cumWords: IntArray,
        images: List<ImageBox>
    ): DetectedFigure? {
        val inBandImages = images.filter { it.centerY >= band.top && it.centerY <= band.bottom }
        // Label candidates: in-band sparse lines that are neither bare page numbers nor
        // typographically continuous with a body block (paragraph tails, footnote remnants).
        val candidates = lines.indices.filter { i ->
            !isBody[i] &&
                lines[i].centerY >= band.top && lines[i].centerY <= band.bottom &&
                !lines[i].numericOnly &&
                !(hasBody && bodyAdjacent[i])
        }
        val minLabelLines = if (hasBody) {
            FigureDetectionConstants.MIN_LABEL_LINES
        } else {
            // No body text: a full-page diagram carries many scattered labels; chapter
            // openers and title pages (a heading or two) must not become figures.
            FigureDetectionConstants.NO_BODY_MIN_LABEL_LINES
        }
        val qualifies = inBandImages.isNotEmpty() ||
            (candidates.size >= minLabelLines && isSparselabelCluster(lines, candidates, cumWords))
        if (!qualifies) return null

        // Content bounding box: union of candidate label lines and images, padded so line
        // art at the box edge survives the crop. The band itself is only the trigger —
        // cropping the raw gap would include empty leading/trailing whitespace.
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        for (i in candidates) {
            val line = lines[i]
            left = minOf(left, line.left); top = minOf(top, line.top)
            right = maxOf(right, line.right); bottom = maxOf(bottom, line.bottom)
        }
        for (image in inBandImages) {
            left = minOf(left, image.left); top = minOf(top, image.top)
            right = maxOf(right, image.right); bottom = maxOf(bottom, image.bottom)
        }

        val padX = FigureDetectionConstants.BOX_PADDING_FRACTION * pageWidth
        val padY = FigureDetectionConstants.BOX_PADDING_FRACTION * pageHeight
        left = (left - padX).coerceAtLeast(0f)
        top = (top - padY).coerceAtLeast(0f)
        right = (right + padX).coerceAtMost(pageWidth)
        bottom = (bottom + padY).coerceAtMost(pageHeight)

        val boxWidth = right - left
        val boxHeight = bottom - top
        if (boxWidth < FigureDetectionConstants.MIN_REGION_DIMENSION_FRACTION * pageWidth) return null
        if (boxHeight < FigureDetectionConstants.MIN_REGION_DIMENSION_FRACTION * pageHeight) return null

        // Label word range: only when the candidate lines are a contiguous run in reading
        // order with no body line inside it — otherwise blanking the range would eat prose.
        var labelStart = -1
        var labelEnd = -1
        var anchorWordOffset: Int
        if (candidates.isNotEmpty()) {
            val first = candidates.first()
            val last = candidates.last()
            val contiguousSparseRun = (first..last).all { i -> !isBody[i] }
            if (contiguousSparseRun) {
                labelStart = cumWords[first]
                labelEnd = cumWords[last + 1] - 1
                if (labelEnd < labelStart) {
                    labelStart = -1
                    labelEnd = -1
                }
            }
            anchorWordOffset = cumWords[first]
        } else {
            // Image-only band: the figure sits after every line that ends above the band.
            anchorWordOffset = 0
            for (i in lines.indices) {
                if (lines[i].centerY < band.top) anchorWordOffset = cumWords[i + 1]
            }
        }

        return DetectedFigure(
            left = left / pageWidth,
            top = top / pageHeight,
            width = boxWidth / pageWidth,
            height = boxHeight / pageHeight,
            aspectRatio = boxHeight / boxWidth,
            anchorWordOffset = anchorWordOffset,
            labelWordStart = labelStart,
            labelWordEnd = labelEnd
        )
    }

    /**
     * True when an image-less band's candidate lines look like DIAGRAM LABELS rather than an
     * ordinary text block: vertically sparse (labels scatter over a mostly-empty band;
     * verses/footnotes stack at text rhythm) and bounded total word volume. Minimum line
     * count is enforced by the caller (it differs for pages without body text).
     */
    private fun isSparselabelCluster(
        lines: List<TextLine>,
        inBandOrdinals: List<Int>,
        cumWords: IntArray
    ): Boolean {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var lineHeightSum = 0f
        var wordSum = 0
        for (i in inBandOrdinals) {
            val line = lines[i]
            top = minOf(top, line.top)
            bottom = maxOf(bottom, line.bottom)
            lineHeightSum += line.bottom - line.top
            wordSum += cumWords[i + 1] - cumWords[i]
        }
        if (wordSum > FigureDetectionConstants.MAX_LABEL_WORDS_WITHOUT_IMAGE) return false
        val contentHeight = bottom - top
        if (contentHeight <= 0f) return false
        return lineHeightSum / contentHeight <= FigureDetectionConstants.MAX_LABEL_LINE_DENSITY
    }

    /**
     * Median positive gap between consecutive body lines (sorted by top), falling back to
     * the median body line height. The page's vertical text rhythm reference for both band
     * candidacy and label-adjacency exclusion.
     */
    private fun medianBodyReference(bodyLines: List<TextLine>): Float {
        if (bodyLines.isEmpty()) return 0f
        val gaps = mutableListOf<Float>()
        for (i in 0 until bodyLines.size - 1) {
            val gap = bodyLines[i + 1].top - bodyLines[i].bottom
            if (gap > 0f) gaps.add(gap)
        }
        return median(gaps) ?: median(bodyLines.map { it.bottom - it.top }) ?: 0f
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    // Punctuation ordinary prose actually uses; anything else counts toward the gibberish
    // symbol fraction.
    private val BASIC_PUNCTUATION = setOf(
        '.', ',', ';', ':', '\'', '"', '(', ')', '-', '?', '!', '[', ']', '/'
    )

    /** True for a bare page-number line: only digits once punctuation is stripped. */
    fun isBareNumber(text: String): Boolean {
        val core = text.trim().trim { !it.isLetterOrDigit() }
        if (core.isEmpty() || core.length > FigureDetectionConstants.NUMERIC_LINE_MAX_DIGITS) return false
        return core.all { it.isDigit() }
    }

    /**
     * True when a line's text looks like OCR garbage — the invisible text layer scanned
     * books carry over plate images ("bar«3 pbv-w& «< rui»K«rt*»...").
     * Measured as the fraction of non-space characters that are neither alphanumeric nor
     * ordinary punctuation; prose sits near 0.02, mangled OCR near 0.3.
     */
    fun isGibberish(text: String): Boolean {
        var counted = 0
        var symbols = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            counted++
            if (!ch.isLetterOrDigit() && ch !in BASIC_PUNCTUATION) symbols++
        }
        if (counted == 0) return false
        return symbols.toFloat() / counted > FigureDetectionConstants.GIBBERISH_SYMBOL_FRACTION
    }
}
