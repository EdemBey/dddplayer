package top.rootu.dddplayer.logic

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.model.SkipSegment

@RunWith(RobolectricTestRunner::class)
class SkipSegmentsLogicTest {

    private val opening = SkipSegment("op", 0, 89_000)
    private val ending = SkipSegment("ed", 1273_000, 1372_500)

    @Test
    fun `inline array is read in seconds and sorted`() {
        val arr = JSONArray("""[{"type":"ed","startTime":1273,"endTime":1372.5},{"type":"op","startTime":0,"endTime":89}]""")
        assertEquals(listOf(opening, ending), SkipSegmentsLogic.parseInline(arr))
    }

    @Test
    fun `data format of YummyAnime and kopenkin`() {
        val json = """{"data":[{"type":"op","startTime":0,"endTime":89},{"type":"ed","startTime":1273,"endTime":1372.5}]}"""
        assertEquals(listOf(opening, ending), SkipSegmentsLogic.parse(json))
    }

    @Test
    fun `AniSkip results with mixed types, recap becomes a recap segment`() {
        val json = """{"found":true,"results":[
            {"interval":{"startTime":0,"endTime":89},"skipType":"mixed-op","skipId":"a","episodeLength":1376},
            {"interval":{"startTime":1273,"endTime":1372.5},"skipType":"ed","skipId":"b","episodeLength":1376},
            {"interval":{"startTime":100,"endTime":160},"skipType":"recap","skipId":"c","episodeLength":1376}
        ],"message":"Successfully found skip times","statusCode":200}"""
        assertEquals(listOf(opening, SkipSegment("recap", 100_000, 160_000), ending), SkipSegmentsLogic.parse(json))
    }

    @Test
    fun `TheIntroDB maps types, null start and end, drops junk and preview`() {
        val json = """{"tmdb_id":1,"type":"tv",
            "intro":[{"start_ms":314517,"end_ms":331147}],
            "recap":[{"start_ms":null,"end_ms":60000},{"start_ms":0,"end_ms":0}],
            "credits":[{"start_ms":2848346,"end_ms":null},{"start_ms":0,"end_ms":64959},{"start_ms":100000,"end_ms":102000}],
            "preview":[{"start_ms":2900000,"end_ms":2950000}]}"""
        assertEquals(
            listOf(
                SkipSegment("recap", 0, 60_000),
                SkipSegment("op", 314_517, 331_147),
                SkipSegment("ed", 2848_346, 2901_000)
            ),
            SkipSegmentsLogic.parse(json, 2901_000)
        )
        // Без длительности end_ms: null не к чему привязать — сегмент отбрасывается
        assertEquals(
            listOf(SkipSegment("recap", 0, 60_000), SkipSegment("op", 314_517, 331_147)),
            SkipSegmentsLogic.parse(json)
        )
        // Массивов нет вовсе
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("""{"tvdb_id":1,"type":"tv"}""", 1000_000))
    }

    @Test
    fun `IntroDB single objects, nulls and post credits`() {
        val json = """{"imdb_id":"tt1","intro":{"start_ms":314500,"end_ms":331000,"confidence":1},
            "recap":null,"outro":{"start_ms":2843000,"end_ms":2901000},"post_credits":{"start_ms":2950000,"end_ms":3000000},
            "no_intro":false}"""
        assertEquals(
            listOf(SkipSegment("op", 314_500, 331_000), SkipSegment("ed", 2843_000, 2901_000)),
            SkipSegmentsLogic.parse(json, 2901_000)
        )
        // Все поля null — данных нет
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("""{"intro":null,"recap":null,"outro":null,"post_credits":null}"""))
        // Короче 5 с и титры с нуля — мусор
        assertEquals(
            emptyList<SkipSegment>(),
            SkipSegmentsLogic.parse("""{"intro":{"start_ms":1000,"end_ms":4000},"outro":{"start_ms":0,"end_ms":90000}}""")
        )
    }

    @Test
    fun `first address with an opening wins and the rest are not requested`() {
        val asked = mutableListOf<String>()
        val result = SkipSegmentsLogic.firstNonEmpty(listOf("a", "b", "c")) { url ->
            asked.add(url)
            if (url == "b") listOf(opening) else emptyList()
        }
        assertEquals(listOf(opening), result)
        assertEquals(listOf("a", "b"), asked)

        // Первый адрес дал опенинг — второй не запрашивается
        asked.clear()
        assertEquals(listOf(opening), SkipSegmentsLogic.firstNonEmpty(listOf("a", "b")) { asked.add(it); listOf(opening) })
        assertEquals(listOf("a"), asked)

        // Первый дал только эндинг — пробуем дальше, побеждает ответ с опенингом
        asked.clear()
        val full = listOf(opening, ending)
        assertEquals(
            full,
            SkipSegmentsLogic.firstNonEmpty(listOf("a", "b")) { asked.add(it); if (it == "a") listOf(ending) else full }
        )
        assertEquals(listOf("a", "b"), asked)

        // Опенинга нет нигде — первый непустой ответ
        val edOnly = listOf(SkipSegment("ed", 900_000, 960_000))
        assertEquals(
            edOnly,
            SkipSegmentsLogic.firstNonEmpty(listOf("a", "b", "c")) { if (it == "c") listOf(ending) else if (it == "b") edOnly else emptyList() }
        )
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.firstNonEmpty(listOf("a")) { emptyList() })
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.firstNonEmpty(emptyList()) { listOf(opening) })
    }

    @Test
    fun `recap gets its own chapter title and button value stays seconds`() {
        val result = merge(emptyList(), listOf(SkipSegment("recap", 0, 60_000), SkipSegment("op", 300_000, 330_000)))
        assertEquals(
            listOf(Chapter(0, "Recap"), Chapter(60_000, "Episode"), Chapter(300_000, "Opening"), Chapter(330_000, "Episode")),
            result
        )
        assertEquals(10, SkipSegmentsLogic.normalizeButtonSeconds(10))
        assertEquals(0, SkipSegmentsLogic.normalizeButtonSeconds(0))
        // Значение вне списка — весь сегмент
        assertEquals(0, SkipSegmentsLogic.normalizeButtonSeconds(7))
    }

    @Test
    fun `broken input gives no segments`() {
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("not json"))
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("""{"found":false,"results":[]}"""))
        val arr = JSONArray("""[{"type":"op","startTime":90,"endTime":10},{"type":"op","startTime":"x"},{"type":"op"}]""")
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parseInline(arr))
    }

    @Test
    fun `active segment ends a second before its end`() {
        val list = listOf(opening, ending)
        assertEquals(opening, SkipSegmentsLogic.active(list, 0))
        assertEquals(opening, SkipSegmentsLogic.active(list, 87_999))
        assertNull(SkipSegmentsLogic.active(list, 88_000))
        assertNull(SkipSegmentsLogic.active(list, 500_000))
        assertEquals(ending, SkipSegmentsLogic.active(list, 1300_000))
    }

    @Test
    fun `duration is filled in with up to 3 decimals only where it is zero`() {
        val url = "https://x/api/player/skip-times?id=1&duration=0&n=2"
        assertEquals("https://x/api/player/skip-times?id=1&duration=1512.085&n=2", SkipSegmentsLogic.urlWithDuration(url, 1512_085))
        assertEquals("https://x/api/player/skip-times?id=1&duration=1512&n=2", SkipSegmentsLogic.urlWithDuration(url, 1512_000))
        assertEquals("https://x?duration=1512.5", SkipSegmentsLogic.urlWithDuration("https://x?duration=0", 1512_500))
        assertEquals("https://x?duration=0.05", SkipSegmentsLogic.urlWithDuration("https://x?duration=0", 50))
        assertEquals(url, SkipSegmentsLogic.urlWithDuration(url, 0))
        assertEquals("https://x?maxduration=0", SkipSegmentsLogic.urlWithDuration("https://x?maxduration=0", 90_000))
        assertEquals("https://x?duration=05", SkipSegmentsLogic.urlWithDuration("https://x?duration=05", 90_000))
        // Оба параметра сразу: episodeLength целыми секундами, duration с долями
        assertEquals(
            "https://x?episodeLength=1512&duration=1512.085",
            SkipSegmentsLogic.urlWithDuration("https://x?episodeLength=0&duration=0", 1512_085)
        )
    }

    @Test
    fun `two endings in a row give two Ending chapters and no stray Episode between them`() {
        // Песня (AniSkip) 13:40–15:05, затем титры (YummyAnime) 15:04–16:00
        val song = SkipSegment("ed", 820_000, 905_000)
        val credits = SkipSegment("ed", 904_000, 960_000)
        assertEquals(
            listOf(Chapter(0, "Episode"), Chapter(820_000, "Ending"), Chapter(904_000, "Ending"), Chapter(960_000, "Episode")),
            merge(emptyList(), listOf(song, credits))
        )
        // Зазор больше 1,5 с: между ними остается «Эпизод»
        val gap = merge(emptyList(), listOf(song, SkipSegment("ed", 910_000, 960_000)))
        assertEquals(listOf(0L, 820_000L, 905_000L, 910_000L, 960_000L), gap.map { it.startMs })
        // Последние титры до конца видео: хвостовой главы нет
        val tail = merge(emptyList(), listOf(song, SkipSegment("ed", 904_000, 1399_500)))
        assertEquals(listOf(0L, 820_000L, 904_000L), tail.map { it.startMs })
    }

    @Test
    fun `combine keeps both sources unless they overlap by more than 5 s`() {
        val yummy = SkipSegment("ed", 904_000, 960_000)
        val aniskip = SkipSegment("ed", 820_000, 905_000)
        // Перекрытие 1 с: оба
        assertEquals(listOf(aniskip, yummy), SkipSegmentsLogic.combine(listOf(yummy), listOf(aniskip)))
        // Ровно 5 с: еще оба
        val five = SkipSegment("ed", 820_000, 909_000)
        assertEquals(listOf(five, yummy), SkipSegmentsLogic.combine(listOf(yummy), listOf(five)))
        // 60 с перекрытия: встроенный побеждает
        val yummyOp = SkipSegment("op", 0, 90_000)
        assertEquals(listOf(yummyOp), SkipSegmentsLogic.combine(listOf(yummyOp), listOf(SkipSegment("op", 30_000, 120_000))))
        // Короткий скачанный целиком внутри встроенного: отбрасывается, хотя перекрытие меньше 5 с
        assertEquals(listOf(yummyOp), SkipSegmentsLogic.combine(listOf(yummyOp), listOf(SkipSegment("op", 0, 4_000))))
        // Один из источников пуст
        assertEquals(listOf(aniskip), SkipSegmentsLogic.combine(emptyList(), listOf(aniskip)))
        assertEquals(listOf(yummy), SkipSegmentsLogic.combine(listOf(yummy), emptyList()))
    }

    @Test
    fun `ending right after an opening keeps its title`() {
        val result = merge(emptyList(), listOf(SkipSegment("op", 60_000, 150_000), SkipSegment("ed", 150_500, 240_000)))
        assertEquals("Ending", result.single { it.startMs == 150_500L }.title)
        assertEquals(emptyList<Chapter>(), result.filter { it.title == "Episode" && kotlin.math.abs(it.startMs - 150_000) < 1500 })
    }

    @Test
    fun `episode length is filled in only where it is zero`() {
        assertEquals(
            "https://api.aniskip.com/v2/skip-times/20/2?types[]=op&episodeLength=1376",
            SkipSegmentsLogic.urlWithDuration("https://api.aniskip.com/v2/skip-times/20/2?types[]=op&episodeLength=0", 1376_400)
        )
        assertEquals("https://x?maxEpisodeLength=0", SkipSegmentsLogic.urlWithDuration("https://x?maxEpisodeLength=0", 90_000))
        assertEquals("https://x?episodeLength=05&a=1", SkipSegmentsLogic.urlWithDuration("https://x?episodeLength=05&a=1", 90_000))
        assertEquals("https://x?episodeLength=0&a=1", SkipSegmentsLogic.urlWithDuration("https://x?episodeLength=0&a=1", 0))
        assertEquals("https://x?episodeLength=90&a=1", SkipSegmentsLogic.urlWithDuration("https://x?episodeLength=0&a=1", 90_000))
    }

    @Test
    fun `button stays for the whole segment when seconds is 0`() {
        val list = listOf(opening, ending)
        assertEquals(opening, SkipSegmentsLogic.buttonSegment(list, 80_000, 0))
        assertNull(SkipSegmentsLogic.buttonSegment(list, 88_500, 0))
    }

    @Test
    fun `button hides after the chosen seconds, ok still uses the whole segment`() {
        val list = listOf(opening, ending)
        assertEquals(opening, SkipSegmentsLogic.buttonSegment(list, 9_999, 10))
        assertNull(SkipSegmentsLogic.buttonSegment(list, 10_000, 10))
        // Отсчет от начала сегмента, а не от нуля
        assertEquals(ending, SkipSegmentsLogic.buttonSegment(list, 1282_999, 10))
        assertNull(SkipSegmentsLogic.buttonSegment(list, 1283_000, 10))
        // active() для OK и автопропуска от настройки не зависит
        assertEquals(opening, SkipSegmentsLogic.active(list, 50_000))
    }

    private fun merge(container: List<Chapter>, segments: List<SkipSegment>, durationMs: Long = 1_400_000) =
        SkipSegmentsLogic.mergeChapters(container, segments, durationMs, "Opening", "Ending", "Recap", "Episode")

    @Test
    fun `no segments leave chapters unchanged`() {
        val chapters = listOf(Chapter(0, "A"), Chapter(600_000, "B"))
        assertEquals(chapters, merge(chapters, emptyList()))
        assertEquals(emptyList<Chapter>(), merge(emptyList(), emptyList()))
    }

    @Test
    fun `opening and ending become chapters on an empty container`() {
        val result = merge(emptyList(), listOf(SkipSegment("op", 60_000, 150_000), ending))
        assertEquals(
            listOf(
                Chapter(0, "Episode"),
                Chapter(60_000, "Opening"),
                Chapter(150_000, "Episode"),
                Chapter(1273_000, "Ending"),
                Chapter(1372_500, "Episode")
            ),
            result
        )
    }

    @Test
    fun `opening from zero needs no extra first chapter`() {
        assertEquals(
            listOf(Chapter(0, "Opening"), Chapter(89_000, "Episode")),
            merge(emptyList(), listOf(opening))
        )
    }

    @Test
    fun `chapters within 1_5 s are not duplicated`() {
        val container = listOf(Chapter(0, "Intro"), Chapter(88_000, "Part 1"), Chapter(600_000, "Part 2"))
        // Старт на 0 и конец в 89 с попадают рядом с главами контейнера; нулевую главу не добавляем
        assertEquals(container, merge(container, listOf(opening)))
        // Глава контейнера не пустая: «Эпизод» в 0 не добавляется, а удаленный сегмент добавляется
        val far = merge(container, listOf(SkipSegment("ed", 1273_000, 1372_500)))
        assertEquals(listOf(0L, 88_000L, 600_000L, 1273_000L, 1372_500L), far.map { it.startMs })
    }

    @Test
    fun `ending that runs to the very end gives no trailing chapter`() {
        val result = merge(listOf(Chapter(0, "A")), listOf(SkipSegment("ed", 1300_000, 1399_500)))
        assertEquals(listOf(Chapter(0, "A"), Chapter(1300_000, "Ending")), result)
        // Длительность неизвестна: конец сегмента остается главой
        assertEquals(2 + 1, merge(listOf(Chapter(0, "A")), listOf(SkipSegment("ed", 1300_000, 1399_500)), 0).size)
    }

    @Test
    fun `band colors and button options match the settings`() {
        assertEquals(6, SkipSegmentsLogic.BAND_COLORS.size)
        assertEquals(0x96FFC400.toInt(), SkipSegmentsLogic.BAND_COLORS[0])
        assertEquals(listOf(1, 2, 3, 4, 5, 10, 15, 20, 30, 0), SkipSegmentsLogic.BUTTON_SECONDS_OPTIONS.toList())
    }

    @Test
    fun `auto skip fires once per segment and not in its last second`() {
        val list = listOf(opening, ending)
        val skipped = mutableSetOf<SkipSegment>()
        assertEquals(opening, SkipSegmentsLogic.autoSkip(list, 0, skipped))
        skipped.add(opening)
        // Перемотали назад в опенинг — второй раз не выкидываем
        assertNull(SkipSegmentsLogic.autoSkip(list, 10_000, skipped))
        assertNull(SkipSegmentsLogic.autoSkip(list, 1372_000, skipped))
        assertEquals(ending, SkipSegmentsLogic.autoSkip(list, 1300_000, skipped))
    }
}
