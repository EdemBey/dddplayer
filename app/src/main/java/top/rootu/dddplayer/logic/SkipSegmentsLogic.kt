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

    /** AniSkip отбирает тайминги по длине серии: episodeLength=0 заменяется на длительность в секундах. */
    fun urlWithDuration(url: String, durationMs: Long): String =
        if (durationMs <= 0) url else url.replace(zeroLength, "episodeLength=${durationMs / 1000}")

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
