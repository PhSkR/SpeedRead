package com.speedread.rsvp.data.parser

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Content-stream walker that records the placed bounding box of every raster image XObject
 * on a page (the standard PrintImageLocations recipe). The CTM places the image's unit
 * square in bottom-left-origin user space; boxes are emitted flipped to the top-left-origin
 * convention shared with [LineGeometryTextStripper] and [FigureRegionDetector].
 *
 * [pageHeight]/[pageOffsetX]/[pageOffsetY] come from the page crop box so coordinates are
 * relative to the visible page area.
 */
class ImageRegionCollector(
    private val pageHeight: Float,
    private val pageOffsetX: Float,
    private val pageOffsetY: Float
) : PDFStreamEngine() {

    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    val images = mutableListOf<Box>()

    init {
        addOperator(Concatenate())
        addOperator(DrawObject())
        addOperator(SetGraphicsStateParameters())
        addOperator(Save())
        addOperator(Restore())
        addOperator(SetMatrix())
    }

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        if (operator.name == "Do") {
            val objectName = operands.getOrNull(0) as? COSName
            val xobject = objectName?.let { resources.getXObject(it) }
            if (xobject is PDImageXObject) {
                val ctm = graphicsState.currentTransformationMatrix
                val x0 = ctm.translateX - pageOffsetX
                val y0 = ctm.translateY - pageOffsetY
                val x1 = x0 + ctm.scalingFactorX
                val y1 = y0 + ctm.scalingFactorY
                // Negative scale factors flip the image; normalize to min/max before the
                // bottom-left to top-left origin flip.
                val leftPt = minOf(x0, x1)
                val rightPt = maxOf(x0, x1)
                val bottomLeftLow = minOf(y0, y1)
                val bottomLeftHigh = maxOf(y0, y1)
                images.add(
                    Box(
                        left = leftPt,
                        top = pageHeight - bottomLeftHigh,
                        right = rightPt,
                        bottom = pageHeight - bottomLeftLow
                    )
                )
                return
            }
            if (xobject is PDFormXObject) {
                showForm(xobject)
                return
            }
        }
        super.processOperator(operator, operands)
    }
}
