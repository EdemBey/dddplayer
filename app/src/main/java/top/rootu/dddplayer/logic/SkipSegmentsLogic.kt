package top.rootu.dddplayer.logic

import org.json.JSONArray
import org.json.JSONObject
import top.rootu.dddplayer.model.SkipSegment

/**
 * Сегменты пропуска опенинга/эндинга/пересказа (X-DDD-Segments). Чистая логика, без плеера.
 * Значение заголовка — объект: индекс серии -> массив сегментов
 * [{"type":"op","startTime":..,"endTime":..}] (секунды), адрес (или список адресов), откуда плеер скачает их сам,
 * или объект {"segments":[..],"url":..} с тем и другим. Типы: op, ed, recap.
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

    /**
     * Ответ по адресу: {"data":[...]}, AniSkip {"results":[{"interval":{..},"skipType":..}]}, массив,
     * TheIntroDB v4 {"intro":[{"start_ms","end_ms"}],"recap":[..],"credits":[..]} или IntroDB
     * {"intro":{..}|null,"recap":..,"outro":..}. Длительность нужна для end_ms: null (до конца видео).
     */
    fun parse(json: String, durationMs: Long = 0L): List<SkipSegment> {
        val text = json.trim()
        return try {
            if (text.startsWith("[")) return parseInline(JSONArray(text))
            val root = JSONObject(text)
            root.optJSONArray("data")?.let { return parseInline(it) }
            val results = root.optJSONArray("results")
            if (results == null) return parseSeries(root, durationMs)
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

    /** Допуск, в котором длина видео из результата AniSkip считается длиной играющего видео. */
    private const val LENGTH_MATCH_MS = 1500L

    /**
     * AniSkip с подбором по длине: номера серий там бывают сдвинуты (JoJo SBR: опенинг 2-й серии лежит в 1-й),
     * но у каждого результата есть episodeLength — длина видео, на котором его отметили. Оставляем только
     * результаты с длиной играющего видео (±1,5 с); без длительности подбирать не по чему.
     */
    fun parseAniSkipMatching(json: String, durationMs: Long): List<SkipSegment> {
        if (durationMs <= 0) return emptyList()
        return try {
            val results = JSONObject(json.trim()).optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<SkipSegment>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val lengthMs = (obj.optDouble("episodeLength", 0.0) * 1000).toLong()
                if (lengthMs <= 0 || kotlin.math.abs(lengthMs - durationMs) > LENGTH_MATCH_MS) continue
                val interval = obj.optJSONObject("interval") ?: continue
                segment(obj.optString("skipType"), interval)?.let { list.add(it) }
            }
            list.sortedBy { it.startMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Ответы AniSkip по серии и соседним: каждый тип (op/ed/recap) берется из первого ответа, где он есть.
     * Соседние серии той же длины лишь дополняют типы, которых у самой серии нет, и не добавляют второй опенинг.
     */
    fun mergeByType(lists: List<List<SkipSegment>>): List<SkipSegment> =
        lists.fold(emptyList<SkipSegment>()) { acc, next ->
            acc + next.filter { n -> acc.none { it.type == n.type } }
        }.sortedBy { it.startMs }

    // Ключи сериальных баз -> тип сегмента; preview и post_credits не пропускаем
    private val seriesTypes = mapOf("intro" to "op", "recap" to "recap", "credits" to "ed", "outro" to "ed")

    /** Минимальная длина сегмента из баз сериалов: короче — заглушки вроде recap 0–0. */
    private const val MIN_SERIES_MS = 5000L

    private fun parseSeries(root: JSONObject, durationMs: Long): List<SkipSegment> {
        val list = mutableListOf<SkipSegment>()
        for ((key, type) in seriesTypes) {
            // TheIntroDB отдает массив записей, IntroDB — одну запись или null
            val entries = root.optJSONArray(key)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                ?: listOfNotNull(root.optJSONObject(key))
            for (obj in entries) {
                // start_ms: null — с начала, end_ms: null — до конца видео (без длительности отбрасываем)
                val start = if (obj.isNull("start_ms")) 0L else obj.optLong("start_ms", 0L)
                val end = if (obj.isNull("end_ms")) durationMs else obj.optLong("end_ms", 0L)
                if (end <= 0 || start < 0 || end - start < MIN_SERIES_MS) continue
                // Титры с нуля — заведомо ошибочные данные
                if (type == "ed" && start == 0L) continue
                list.add(SkipSegment(type, start, end))
            }
        }
        return list.sortedBy { it.startMs }
    }

    /**
     * Результат по списку адресов: первый ответ с опенингом (дальше не запрашиваем). Если опенинга нет нигде,
     * берется первый непустой ответ: один адрес может отдать только эндинг, а другой — полный набор.
     */
    fun firstNonEmpty(urls: List<String>, load: (String) -> List<SkipSegment>): List<SkipSegment> {
        var firstNonEmpty: List<SkipSegment>? = null
        for (url in urls) {
            val list = load(url)
            if (list.isEmpty()) continue
            if (list.any { it.type == "op" }) return list
            if (firstNonEmpty == null) firstNonEmpty = list
        }
        return firstNonEmpty.orEmpty()
    }

    /** Сегмент, в котором сейчас позиция. В последнюю секунду кнопка уже не нужна. */
    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1000 }

    /** Сегмент для кнопки: как active, но при buttonSeconds > 0 кнопка гаснет через столько секунд после начала сегмента. */
    fun buttonSegment(segments: List<SkipSegment>, positionMs: Long, buttonSeconds: Int): SkipSegment? =
        active(segments, positionMs)?.takeIf { buttonSeconds <= 0 || positionMs < it.startMs + buttonSeconds * 1000L }

    /** Перекрытие скачанного сегмента со встроенным, больше которого встроенный (YummyAnime) побеждает. */
    private const val OVERLAP_MS = 5000L

    /**
     * Два источника таймингов серии: встроенные сегменты остаются все, скачанные (AniSkip/kopenkin)
     * добавляются, если не перекрывают встроенный больше чем на 5 с. Касание и малое перекрытие — оба сегмента.
     */
    fun combine(inline: List<SkipSegment>, downloaded: List<SkipSegment>): List<SkipSegment> {
        if (inline.isEmpty() || downloaded.isEmpty()) return inline.ifEmpty { downloaded }
        val extra = downloaded.filter { d ->
            // Целиком внутри встроенного тоже отбрасываем: короткий скачанный не должен дробить встроенный
            inline.none {
                minOf(it.endMs, d.endMs) - maxOf(it.startMs, d.startMs) > OVERLAP_MS ||
                    (d.startMs >= it.startMs && d.endMs <= it.endMs)
            }
        }
        return (inline + extra).sortedBy { it.startMs }
    }

    /** Окно, в котором две главы считаются одной. */
    private const val NEAR_MS = 1500L

    /**
     * Главы контейнера плюс сегменты как главы: «Опенинг/Эндинг/Пересказ» на старте сегмента и «Эпизод» на его конце,
     * чтобы кнопки следующей/предыдущей главы и меню глав работали с ними. Без сегментов список не меняется.
     */
    fun mergeChapters(
        container: List<Chapter>,
        segments: List<SkipSegment>,
        durationMs: Long,
        openingTitle: String,
        endingTitle: String,
        recapTitle: String,
        episodeTitle: String
    ): List<Chapter> {
        if (segments.isEmpty()) return container
        val result = container.toMutableList()
        fun hasNear(ms: Long) = result.any { kotlin.math.abs(it.startMs - ms) < NEAR_MS }

        // Сначала все старты: конец одного сегмента не должен вытеснить название соседнего
        for (seg in segments) {
            val title = when (seg.type) {
                "ed" -> endingTitle
                "recap" -> recapTitle
                else -> openingTitle
            }
            if (!hasNear(seg.startMs)) result.add(Chapter(seg.startMs, title))
        }
        for (seg in segments) {
            if (durationMs > 0 && seg.endMs >= durationMs - 1000) continue
            if (!hasNear(seg.endMs)) result.add(Chapter(seg.endMs, episodeTitle))
        }
        // Без глав контейнера у «предыдущей главы» до первого сегмента не было бы цели
        if (container.isEmpty() && !hasNear(0)) result.add(Chapter(0, episodeTitle))
        return result.sortedBy { it.startMs }
    }

    /** Цвета полосы сегментов (0xAARRGGBB) по индексу настроек `skip_color_<тип>`; индекс 0 — прежний желтый. */
    val BAND_COLORS = intArrayOf(
        0x96FFC400.toInt(), // жёлтый
        0x96FFFFFF.toInt(), // белый
        0xA0E53935.toInt(), // красный
        0x9643A047.toInt(), // зелёный
        0xA01E88E5.toInt(), // синий
        0xA08E24AA.toInt()  // фиолетовый
    )

    /** Варианты «Кнопка пропуска» в порядке списка, с: 0 — весь сегмент, он последний. */
    val BUTTON_SECONDS_OPTIONS = intArrayOf(1, 2, 3, 4, 5, 10, 15, 20, 30, 0)

    /** Сохраненное значение вне списка вариантов считается «весь сегмент». */
    fun normalizeButtonSeconds(seconds: Int): Int = if (seconds in BUTTON_SECONDS_OPTIONS) seconds else 0

    /** Типы сегментов; совпадают с суффиксами ключей настроек (`auto_skip_<тип>`, `skip_color_<тип>`). */
    val TYPES = listOf("op", "ed", "recap")

    /** Цвет полосы по умолчанию для типа: индекс в BAND_COLORS (опенинг жёлтый, эндинг красный, пересказ синий). */
    fun defaultColorIndex(type: String): Int = when (type) {
        "ed" -> 2
        "recap" -> 4
        else -> 0
    }

    /** Сегмент для автопропуска: тот, где позиция, если его тип включен и его еще не пропускали в этой серии. */
    fun autoSkip(
        segments: List<SkipSegment>,
        positionMs: Long,
        skipped: Set<SkipSegment>,
        enabledTypes: Set<String>
    ): SkipSegment? =
        active(segments.filter { it.type in enabledTypes && it !in skipped }, positionMs)

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

    // op/ed/recap и их «смешанные» варианты у AniSkip; остальное (preview) не пропускаем
    private fun segment(rawType: String, obj: JSONObject): SkipSegment? {
        val type = when (rawType.trim().lowercase()) {
            "op", "mixed-op" -> "op"
            "ed", "mixed-ed" -> "ed"
            "recap" -> "recap"
            else -> return null
        }
        val start = obj.optDouble("startTime", Double.NaN)
        val end = obj.optDouble("endTime", Double.NaN)
        if (start.isNaN() || end.isNaN() || end <= start || start < 0) return null
        return SkipSegment(type, (start * 1000).toLong(), (end * 1000).toLong())
    }
}
