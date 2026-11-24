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
