package top.rootu.dddplayer.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.widget.SeekBar
import top.rootu.dddplayer.model.SkipSegment

// Жёлтый, как у опенинга по умолчанию
internal val DEFAULT_SKIP_COLOR = Color.argb(150, 255, 196, 0)

/**
 * SeekBar that draws a small tick for every chapter start.
 *
 * Media3 does not expose chapters, so the positions come from the chapter
 * loader via the player view model.
 */
class ChapterSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Опенинг/эндинг (X-DDD-Segments): полупрозрачная полоса поверх дорожки, как и метки глав
    private val skipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SKIP_COLOR
    }
    private var skipColors: Map<String, Int> = emptyMap()
    private var skipSegments: List<SkipSegment> = emptyList()
    private var skipDurationMs = 0L

    private var chapterStarts = LongArray(0)
    private var totalDurationMs = 0L

    /** Positions are absolute milliseconds; ticks are skipped when duration is unknown. */
    fun setChapterStarts(starts: List<Long>, durationMs: Long) {
        chapterStarts = starts.toLongArray()
        totalDurationMs = durationMs
        Log.d("ChapterLoader", "seekbar: chapters=${starts.size}, duration=$durationMs")
        invalidate()
    }

    // Цвет полосы по типу сегмента (op, ed, recap); неизвестный тип рисуется цветом skipPaint по умолчанию
    fun setSkipColors(colors: Map<String, Int>) {
        skipColors = colors
        invalidate()
    }

    fun setSkipSegments(segments: List<SkipSegment>, durationMs: Long) {
        skipSegments = segments
        skipDurationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSkipSegments(canvas)
        if (chapterStarts.isEmpty() || totalDurationMs <= 0L) return

        // Match the thumb centre, not the raw padding: AbsSeekBar positions the
        // thumb at padding + (thumbWidth / 2 - thumbOffset) .. width - padding - inset.
        val thumbWidth = thumb?.intrinsicWidth ?: 0
        val inset = if (thumbWidth > 0) thumbWidth / 2f - thumbOffset else 0f
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        if (right <= left) return

        val centerY = height / 2f
        val half = 6f * density
        drawChapterTicks(canvas, left, right, centerY, half, chapterStarts, totalDurationMs, tickPaint)
    }

    private fun drawSkipSegments(canvas: Canvas) {
        if (skipSegments.isEmpty() || skipDurationMs <= 0L) return
        val thumbWidth = thumb?.intrinsicWidth ?: 0
        val inset = if (thumbWidth > 0) thumbWidth / 2f - thumbOffset else 0f
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        if (right <= left) return
        val centerY = height / 2f
        val half = 2f * density
        drawSkipBands(canvas, left, right, centerY, half, skipSegments, skipDurationMs, skipColors, skipPaint)
    }
}

internal fun drawSkipBands(
    canvas: Canvas,
    left: Float,
    right: Float,
    centerY: Float,
    half: Float,
    segments: List<SkipSegment>,
    durationMs: Long,
    colors: Map<String, Int>,
    paint: Paint
) {
    if (segments.isEmpty() || durationMs <= 0L) return
    for (seg in segments) {
        val x1 = left + (right - left) * (seg.startMs.coerceIn(0, durationMs).toFloat() / durationMs)
        val x2 = left + (right - left) * (seg.endMs.coerceIn(0, durationMs).toFloat() / durationMs)
        paint.color = colors[seg.type] ?: DEFAULT_SKIP_COLOR
        if (x2 > x1) canvas.drawRect(x1, centerY - half, x2, centerY + half, paint)
    }
}

internal fun drawChapterTicks(
    canvas: Canvas,
    left: Float,
    right: Float,
    centerY: Float,
    half: Float,
    starts: LongArray,
    durationMs: Long,
    paint: Paint
) {
    if (starts.isEmpty() || durationMs <= 0L) return
    for (start in starts) {
        if (start <= 0L || start >= durationMs) continue
        val x = left + (right - left) * (start.toDouble() / durationMs).toFloat()
        canvas.drawLine(x, centerY - half, x, centerY + half, paint)
    }
}
