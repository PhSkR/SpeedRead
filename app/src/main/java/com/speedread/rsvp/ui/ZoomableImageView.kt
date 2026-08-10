package com.speedread.rsvp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.speedread.rsvp.Constants

/**
 * Minimal pinch-zoom/pan ImageView for the inline-figure full-page viewer. The image starts
 * fit-centered; pinch scales around the gesture focus (clamped to
 * [Constants.FIGURE_VIEWER_MAX_ZOOM]), drag pans within the image bounds, double-tap toggles
 * between fit and [Constants.FIGURE_VIEWER_DOUBLE_TAP_ZOOM]. No external dependency — the
 * app has no other zoom surface to share (PDF page mode is a plain ImageView).
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentZoom()
                var factor = detector.scaleFactor
                val target = (current * factor)
                    .coerceIn(1f, Constants.FIGURE_VIEWER_MAX_ZOOM)
                factor = target / current
                suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampAndApply()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                suppMatrix.postTranslate(-distanceX, -distanceY)
                clampAndApply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentZoom() > 1.01f) {
                    suppMatrix.reset()
                } else {
                    val zoom = Constants.FIGURE_VIEWER_DOUBLE_TAP_ZOOM
                    suppMatrix.postScale(zoom, zoom, e.x, e.y)
                }
                clampAndApply()
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep the dialog's scroll containers (if any) from stealing the gesture mid-zoom.
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetToFit()
    }

    private fun resetToFit() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = minOf(width / dw, height / dh)
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((width - dw * scale) / 2f, (height - dh * scale) / 2f)
        suppMatrix.reset()
        clampAndApply()
    }

    private fun currentZoom(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the zoomed image edge-locked to the view: no gaps once a dimension overflows. */
    private fun clampAndApply() {
        val drawable = drawable
        if (drawable != null && width > 0 && height > 0) {
            drawMatrix.set(baseMatrix)
            drawMatrix.postConcat(suppMatrix)
            val rect = RectF(
                0f, 0f,
                drawable.intrinsicWidth.toFloat(),
                drawable.intrinsicHeight.toFloat()
            )
            drawMatrix.mapRect(rect)

            val dx = when {
                rect.width() <= width -> (width - rect.width()) / 2f - rect.left
                rect.left > 0f -> -rect.left
                rect.right < width -> width - rect.right
                else -> 0f
            }
            val dy = when {
                rect.height() <= height -> (height - rect.height()) / 2f - rect.top
                rect.top > 0f -> -rect.top
                rect.bottom < height -> height - rect.bottom
                else -> 0f
            }
            suppMatrix.postTranslate(dx, dy)
        }
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        imageMatrix = drawMatrix
    }
}
