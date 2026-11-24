package top.rootu.dddplayer.logic

import org.json.JSONArray
import org.json.JSONObject
import top.rootu.dddplayer.model.SkipSegment

/**
 * Сегменты пропуска опенинга/эндинга (X-DDD-Segments). Чистая логика, без плеера.
 * Значение заголовка — объект: индекс серии -> либо массив сегментов
 * [{"type":"op","startTime":..,"endTime":..}] (секунды), либо адрес, откуда плеер скачает их сам.
 */
object SkipSegmentsLogic {

    private val zeroLength = Regex("""(?<=[?&])episodeLength=0(?=&|$)""")
    private val zeroDuration = Regex("""(?<=[?&])duration=0(?=&|$)""")

    /** Массив [{"type","startTime","endTime"}] (YummyAnime и kopenkin). */
    fun parseInline(arr: JSONArray): List<SkipSegment> {
        val result = mutableListOf<SkipSegment>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            segment(obj.optString("type"), obj)?.let { result.add(it) }
        }
        return result.sortedBy { it.startMs }
    }

    /** Ответ по адресу: {"data":[...]}, AniSkip {"results":[{"interval":{..},"skipType":..}]} или сразу массив. */
    fun parse(json: String): List<SkipSegment> {
        val text = json.trim()
        return try {
            if (text.startsWith("[")) return parseInline(JSONArray(text))
            val root = JSONObject(text)
            root.optJSONArray("data")?.let { return parseInline(it) }
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<SkipSegment>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val interval = obj.optJSONObject("interval") ?: continue
                segment(obj.optString("skipType"), interval)?.let { list.add(it) }
            }
            list.sortedBy { it.startMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Сегмент, в котором сейчас позиция. В последнюю секунду кнопка уже не нужна. */
    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1000 }

    /** Сегмент для кнопки: как active, но при buttonSeconds > 0 кнопка гаснет через столько секунд после начала сегмента. */
    fun buttonSegment(segments: List<SkipSegment>, positionMs: Long, buttonSeconds: Int): SkipSegment? =
        active(segments, positionMs)?.takeIf { buttonSeconds <= 0 || positionMs < it.startMs + buttonSeconds * 1000L }

    /** Окно, в котором две главы считаются одной. */
    private const val NEAR_MS = 1500L

    /**
     * Главы контейнера плюс сегменты как главы: «Опенинг/Эндинг» на старте сегмента и «Эпизод» на его конце,
     * чтобы кнопки следующей/предыдущей главы и меню глав работали с ними. Без сегментов список не меняется.
     */
    fun mergeChapters(
        container: List<Chapter>,
        segments: List<SkipSegment>,
        durationMs: Long,
        openingTitle: String,
        endingTitle: String,
        episodeTitle: String
    ): List<Chapter> {
        if (segments.isEmpty()) return container
        val result = container.toMutableList()
        fun hasNear(ms: Long) = result.any { kotlin.math.abs(it.startMs - ms) < NEAR_MS }

        // Сначала все старты: конец одного сегмента не должен вытеснить название соседнего
        for (seg in segments) {
            if (!hasNear(seg.startMs)) result.add(Chapter(seg.startMs, if (seg.type == "ed") endingTitle else openingTitle))
        }
        for (seg in segments) {
            if (durationMs > 0 && seg.endMs >= durationMs - 1000) continue
            if (!hasNear(seg.endMs)) result.add(Chapter(seg.endMs, episodeTitle))
        }
        // Без глав контейнера у «предыдущей главы» до первого сегмента не было бы цели
        if (container.isEmpty() && !hasNear(0)) result.add(Chapter(0, episodeTitle))
        return result.sortedBy { it.startMs }
    }

    /** Цвета полосы сегментов (0xAARRGGBB) по индексу настройки `skip_segments_color`; индекс 0 — прежний желтый. */
    val BAND_COLORS = intArrayOf(
        0x96FFC400.toInt(), // жёлтый
        0x96FFFFFF.toInt(), // белый
        0xA0E53935.toInt(), // красный
        0x9643A047.toInt(), // зелёный
        0xA01E88E5.toInt(), // синий
        0xA08E24AA.toInt()  // фиолетовый
    )

    /** Варианты «Кнопка пропуска», с: 0 — весь сегмент. */
    val BUTTON_SECONDS_OPTIONS = intArrayOf(0, 5, 10, 15, 30)

    /** Сегмент для автопропуска: тот, где позиция, если его еще не пропускали в этой серии. */
    fun autoSkip(segments: List<SkipSegment>, positionMs: Long, skipped: Set<SkipSegment>): SkipSegment? =
        active(segments, positionMs)?.takeIf { it !in skipped }

    /**
     * AniSkip отбирает тайминги по длине серии, kopenkin — по duration (±2 с, без него отвечает 400):
     * episodeLength=0 заменяется целыми секундами, duration=0 — секундами до 3 знаков (1512.085, 1512).
     */
    fun urlWithDuration(url: String, durationMs: Long): String {
        if (durationMs <= 0) return url
        val frac = (durationMs % 1000).toString().padStart(3, '0').trimEnd('0')
        val seconds = "${durationMs / 1000}" + if (frac.isEmpty()) "" else ".$frac"
        return url.replace(zeroLength, "episodeLength=${durationMs / 1000}")
            .replace(zeroDuration, "duration=$seconds")
    }

    // op/ed и их «смешанные» варианты у AniSkip; остальное (recap, preview) не пропускаем
    private fun segment(rawType: String, obj: JSONObject): SkipSegment? {
        val type = when (rawType.trim().lowercase()) {
            "op", "mixed-op" -> "op"
            "ed", "mixed-ed" -> "ed"
            else -> return null
        }
        val start = obj.optDouble("startTime", Double.NaN)
        val end = obj.optDouble("endTime", Double.NaN)
        if (start.isNaN() || end.isNaN() || end <= start || start < 0) return null
        return SkipSegment(type, (start * 1000).toLong(), (end * 1000).toLong())
    }
}
