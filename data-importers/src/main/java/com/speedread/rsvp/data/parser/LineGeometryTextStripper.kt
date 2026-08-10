package com.speedread.rsvp.data.parser

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * PDFTextStripper that additionally records the text and bounding box of every output line,
 * in emission (reading) order. Every override delegates to super first, so the returned page
 * text is byte-identical to a plain stripper — the import word counting over that text stays
 * exactly aligned with the per-line word counts derived from [collectedLines].
 *
 * Coordinates are direction-adjusted page points with a top-left origin (yDirAdj is the
 * distance from the page top to the glyph baseline).
 */
class LineGeometryTextStripper : PDFTextStripper() {

    class RawLine(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    val collectedLines = mutableListOf<RawLine>()

    private val lineText = StringBuilder()
    private var left = Float.MAX_VALUE
    private var top = Float.MAX_VALUE
    private var right = -Float.MAX_VALUE
    private var bottom = -Float.MAX_VALUE

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        super.writeString(text, textPositions)
        lineText.append(text)
        for (position in textPositions) {
            val x = position.xDirAdj
            val baseline = position.yDirAdj
            left = minOf(left, x)
            right = maxOf(right, x + position.widthDirAdj)
            top = minOf(top, baseline - position.heightDir)
            bottom = maxOf(bottom, baseline)
        }
    }

    override fun writeWordSeparator() {
        super.writeWordSeparator()
        lineText.append(wordSeparator)
    }

    override fun writeLineSeparator() {
        super.writeLineSeparator()
        flushLine()
    }

    override fun writeParagraphEnd() {
        super.writeParagraphEnd()
        flushLine()
    }

    override fun endPage(page: PDPage) {
        super.endPage(page)
        flushLine()
    }

    private fun flushLine() {
        if (lineText.isNotBlank() && left <= right && top <= bottom) {
            collectedLines.add(RawLine(lineText.toString(), left, top, right, bottom))
        }
        lineText.setLength(0)
        left = Float.MAX_VALUE
        top = Float.MAX_VALUE
        right = -Float.MAX_VALUE
        bottom = -Float.MAX_VALUE
    }
}
