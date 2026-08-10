package com.speedread.rsvp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.speedread.rsvp.R

class ExcludeRegion(
    val rect: RectF,
    val cornerRadius: Float
)

// Activity-level dim overlay used by "Dim Surrounding Screen" reader mode. Replaces the
// previous trio of (1) fragment-level dimOverlayTop, (2) fragment-level dimOverlayBottom,
// and (3) activity-level bottomNavDimOverlay, which collectively left un-dimmed gaps:
//   - System status-bar inset above the ScrollView (the fragment's overlay could not
//     extend past the ScrollView's top padding into the inset area).
//   - The 16dp strips to the left and right of rsvpDisplayCard inside the parent
//     ConstraintLayout's android:padding="16dp" — neither top nor bottom overlay covered
//     these because both were anchored to the card's vertical edges.
//   - The system gesture/navigation-bar inset below the BottomNavigationView (the nav
//     reserves bottom padding for it; the nav-sized overlay covered that area, but the
//     same shaded strip on the activity outside the nav was left untouched whenever the
//     nav rendered shorter than its constraint slot).
// Sitting at the activity level lets a single view paint over all of those at once. The
// view draws four rectangles forming a frame around `excludeRect` (the rsvpDisplayCard's
// screen-space bounds, in this view's local coordinates). Empty exclude rect = full
// coverage. Touch handling: taps inside `blockTouchRect` are consumed (used for the
// BottomNavigationView region so accidental taps mid-session do not navigate away);
// every other tap returns false so play/pause/etc. remain reachable through the dim.
class DimMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.reader_dim_overlay)
        isAntiAlias = true
    }
    private val excludeRegions = mutableListOf<ExcludeRegion>()
    private val blockTouchRect = RectF()
    private val clipPath = Path()

    init {
        // Default View skips onDraw when the background is null. We have no background
        // (the dim is painted in onDraw), so explicitly opt back into being drawn.
        setWillNotDraw(false)
    }

    fun setExcludeRegions(regions: List<ExcludeRegion>) {
        excludeRegions.clear()
        excludeRegions.addAll(regions)
        invalidate()
    }

    fun setExcludeRects(rects: List<RectF>) {
        setExcludeRegions(rects.map { ExcludeRegion(it, 0f) })
    }

    fun setExcludeRect(left: Float, top: Float, right: Float, bottom: Float) {
        setExcludeRegions(listOf(ExcludeRegion(RectF(left, top, right, bottom), 0f)))
    }

    fun clearExcludeRect() {
        if (excludeRegions.isEmpty()) return
        excludeRegions.clear()
        invalidate()
    }

    fun setBlockTouchRect(left: Float, top: Float, right: Float, bottom: Float) {
        blockTouchRect.set(left, top, right, bottom)
    }

    fun clearBlockTouchRect() {
        blockTouchRect.setEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (excludeRegions.isEmpty()) {
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        canvas.save()
        for (region in excludeRegions) {
            val rect = region.rect
            if (!rect.isEmpty && rect.width() > 0f && rect.height() > 0f) {
                if (region.cornerRadius > 0f) {
                    clipPath.reset()
                    clipPath.addRoundRect(rect, region.cornerRadius, region.cornerRadius, Path.Direction.CW)
                    canvas.clipOutPath(clipPath)
                } else {
                    canvas.clipOutRect(rect)
                }
            }
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!blockTouchRect.isEmpty && blockTouchRect.contains(event.x, event.y)) {
            return true
        }
        return false
    }
}
