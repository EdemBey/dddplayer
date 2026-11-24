package top.rootu.dddplayer.logic

import top.rootu.dddplayer.model.SkipSegment

/**
 * Подмена звука на участке видео (например, эндинг сериала): приложение
 * передает заголовком X-DDD-Audio-Overlay ссылку на музыку и адрес, который
 * по длительности видео отдает участки (op/ed/recap; разбор — SkipSegmentsLogic.parse). Чистая логика, без плеера.
 */
object EndingOverlayLogic {

    /** Замена не длиннее 90 секунд, как в плеере сайта. */
    private const val MAX_OVERLAY_MS = 90_000L
    private const val SHORT_SEGMENT_MS = 93_000L
    private const val MIN_ENDING_MS = 87_000L

    /** Конец участка, который перекрывается музыкой. */
    fun endOf(segment: SkipSegment): Long =
        if (segment.endMs - segment.startMs <= SHORT_SEGMENT_MS) segment.endMs
        else segment.startMs + MAX_OVERLAY_MS

    /** Эндинг, в который попадает позиция, или null. */
    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? = segments.firstOrNull {
        it.type == "ed" && it.endMs - it.startMs >= MIN_ENDING_MS &&
            positionMs >= it.startMs && positionMs < endOf(it)
    }

    /** Адрес участков: к нему добавляется длительность видео в секундах. */
    fun segmentsUrl(base: String, durationMs: Long): String {
        val separator = if (base.contains('?')) '&' else '?'
        return "$base${separator}duration=${durationMs / 1000.0}"
    }
}
