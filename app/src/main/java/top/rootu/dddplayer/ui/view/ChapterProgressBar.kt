package top.rootu.dddplayer.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.ProgressBar
import top.rootu.dddplayer.model.SkipSegment

class ChapterProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.progressBarStyleHorizontal
) : ProgressBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val skipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SKIP_COLOR
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private var skipColors: Map<String, Int> = emptyMap()
    private var skipSegments: List<SkipSegment> = emptyList()
    private var skipDurationMs = 0L

    private var chapterStarts = LongArray(0)
    private var chapterDurationMs = 0L

    fun setSkipColors(colors: Map<String, Int>) {
        skipColors = colors
        invalidate()
    }

    fun setSkipSegments(segments: List<SkipSegment>, durationMs: Long) {
        skipSegments = segments
        skipDurationMs = durationMs
        invalidate()
    }

    fun setChapterStarts(starts: List<Long>, durationMs: Long) {
        chapterStarts = starts.toLongArray()
        chapterDurationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSkipBands(
            canvas,
            paddingLeft.toFloat(),
            (width - paddingRight).toFloat(),
            height / 2f,
            height / 2f,
            skipSegments,
            skipDurationMs,
            skipColors,
            skipPaint
        )
        drawChapterTicks(
            canvas,
            paddingLeft.toFloat(),
            (width - paddingRight).toFloat(),
            height / 2f,
            height / 2f,
            chapterStarts,
            chapterDurationMs,
            tickPaint
        )
    }
}