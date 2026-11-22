package top.rootu.dddplayer.logic

import org.json.JSONObject

/**
 * Подмена звука на участке видео (например, эндинг сериала): приложение
 * передает заголовком X-DDD-Audio-Overlay ссылку на музыку и адрес, который
 * по длительности видео отдает участки (op/ed/recap). Чистая логика, без плеера.
 */
object EndingOverlayLogic {

    data class Segment(val type: String, val startMs: Long, val endMs: Long)

    /** Замена не длиннее 90 секунд, как в плеере сайта. */
    private const val MAX_OVERLAY_MS = 90_000L
    private const val SHORT_SEGMENT_MS = 93_000L
    private const val MIN_ENDING_MS = 87_000L

    /** Конец участка, который перекрывается музыкой. */
    fun endOf(segment: Segment): Long =
        if (segment.endMs - segment.startMs <= SHORT_SEGMENT_MS) segment.endMs
        else segment.startMs + MAX_OVERLAY_MS

    /** Эндинг, в который попадает позиция, или null. */
    fun active(segments: List<Segment>, positionMs: Long): Segment? = segments.firstOrNull {
        it.type == "ed" && it.endMs - it.startMs >= MIN_ENDING_MS &&
            positionMs >= it.startMs && positionMs < endOf(it)
    }

    /** Адрес участков: к нему добавляется длительность видео в секундах. */
    fun segmentsUrl(base: String, durationMs: Long): String {
        val separator = if (base.contains('?')) '&' else '?'
        return "$base${separator}duration=${durationMs / 1000.0}"
    }

    /** Ответ {"ok":true,"data":[{"type":"ed","startTime":..,"endTime":..}]} в секундах. */
    fun parseSegments(json: String): List<Segment> {
        val data = try {
            JSONObject(json).optJSONArray("data") ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val result = mutableListOf<Segment>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val start = obj.optDouble("startTime", -1.0)
            val end = obj.optDouble("endTime", -1.0)
            if (start < 0 || end <= start) continue
            result.add(Segment(obj.optString("type"), (start * 1000).toLong(), (end * 1000).toLong()))
        }
        return result
    }
}
