package com.speedread.rsvp.data.parser

/**
 * Tuning parameters for import-time figure detection ([FigureRegionDetector]). Module-local
 * (the app's Constants object is not visible from data-importers).
 */
object FigureDetectionConstants {
    // A text line counts as a full-width BODY line when it spans at least this fraction of
    // the widest line on the page AND this fraction of the page width, and carries at least
    // the minimum word count. Everything else is a sparse line (figure labels, captions,
    // page furniture). The absolute page-width floor keeps caption lines from becoming the
    // width reference on pages with no body text at all (full-page plates).
    const val BODY_LINE_MIN_WIDTH_FRACTION = 0.6f
    const val BODY_LINE_MIN_PAGE_WIDTH_FRACTION = 0.45f
    const val BODY_LINE_MIN_WORDS = 4

    // A vertical gap between consecutive body lines is a figure-band candidate when it
    // exceeds both this fraction of the page height and this multiple of the median
    // body-line gap.
    const val MIN_BAND_HEIGHT_FRACTION = 0.06f
    const val GAP_FACTOR = 2.5f

    // A band only becomes a figure when it contains an embedded image, or at least this
    // many sparse text lines (diagram labels). A single sparse line in a margin band is
    // page furniture (running header / page number), not a figure.
    const val MIN_LABEL_LINES = 2

    // Without an embedded image, a label cluster must be VERTICALLY SPARSE to count as a
    // figure: diagram labels are a few fragments scattered over a mostly-empty band, while
    // centered verses, block quotes, and footnote groups stack lines at normal text rhythm.
    // Density = sum of line heights / content-box height; prose blocks sit around 0.7,
    // diagram label clusters around 0.1-0.35.
    const val MAX_LABEL_LINE_DENSITY = 0.45f
    // Same guard by volume: an image-less "figure" made of dozens of words is a text block.
    const val MAX_LABEL_WORDS_WITHOUT_IMAGE = 40

    // Sparse lines typographically continuous with a body block (a paragraph's short last
    // line, footnote tails at a page bottom) are prose, not diagram labels: exclude lines
    // whose distance to the nearest body line is within this factor of the median body-line
    // gap, with a page-fraction floor for robustness on sparse geometry.
    const val LABEL_BODY_ADJACENCY_GAP_FACTOR = 2.5f
    const val LABEL_BODY_ADJACENCY_PAGE_FRACTION = 0.02f

    // A bare page-number line (digits only after stripping punctuation) is page furniture
    // and never counts as figure-label evidence.
    const val NUMERIC_LINE_MAX_DIGITS = 4

    // Pages with no body text need stronger label evidence: a full-page diagram carries many
    // scattered labels, while chapter openers and title pages have only a heading or two.
    const val NO_BODY_MIN_LABEL_LINES = 5

    // OCR gibberish: a line whose symbol fraction (non-alphanumeric, non-basic-punctuation
    // chars over non-space chars) exceeds this is treated as sparse content regardless of
    // width. Scanned plates carry an OCR layer of dense garbage that would otherwise
    // classify as body text and mask the figure. Ordinary prose sits around 0.02; OCR'd
    // manuscript garbage around 0.3; transliterated Greek footnotes around 0.12.
    const val GIBBERISH_SYMBOL_FRACTION = 0.22f

    // Raster images covering more than this fraction of the page are the page scan itself
    // (scanned books wrap every page in one full-page image) and only count as figure
    // evidence on pages with no body text at all (true plates).
    const val IMAGE_MAX_PAGE_AREA_FRACTION = 0.85f

    // Padding applied around the detected content bounding box, as a fraction of the page
    // dimension, so line art at the box edge is not clipped by the crop.
    const val BOX_PADDING_FRACTION = 0.015f

    // Sanity bounds: reject degenerate regions and runaway detection.
    const val MIN_REGION_DIMENSION_FRACTION = 0.05f
    const val MAX_REGIONS_PER_PAGE = 4
}
