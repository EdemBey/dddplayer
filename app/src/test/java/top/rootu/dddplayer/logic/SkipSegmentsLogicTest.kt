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
    fun `AniSkip results with mixed types, recap is dropped`() {
        val json = """{"found":true,"results":[
            {"interval":{"startTime":0,"endTime":89},"skipType":"mixed-op","skipId":"a","episodeLength":1376},
            {"interval":{"startTime":1273,"endTime":1372.5},"skipType":"ed","skipId":"b","episodeLength":1376},
            {"interval":{"startTime":100,"endTime":160},"skipType":"recap","skipId":"c","episodeLength":1376}
        ],"message":"Successfully found skip times","statusCode":200}"""
        assertEquals(listOf(opening, ending), SkipSegmentsLogic.parse(json))
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
        SkipSegmentsLogic.mergeChapters(container, segments, durationMs, "Opening", "Ending", "Episode")

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
        assertEquals(listOf(0, 5, 10, 15, 30), SkipSegmentsLogic.BUTTON_SECONDS_OPTIONS.toList())
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
