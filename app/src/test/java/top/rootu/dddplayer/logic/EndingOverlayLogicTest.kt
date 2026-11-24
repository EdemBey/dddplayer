package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.rootu.dddplayer.model.SkipSegment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EndingOverlayLogicTest {

    private val ending = SkipSegment("ed", 1326_669, 1508_179)
    private val opening = SkipSegment("op", 71_500, 161_315)

    @Test
    fun `ending window is capped at 90 seconds`() {
        assertEquals(1326_669 + 90_000, EndingOverlayLogic.endOf(ending))
    }

    @Test
    fun `short ending keeps its own end`() {
        assertEquals(1440_000, EndingOverlayLogic.endOf(SkipSegment("ed", 1400_000, 1440_000)))
    }

    @Test
    fun `only long endings are replaced`() {
        val segments = listOf(opening, ending)
        assertNull(EndingOverlayLogic.active(segments, 100_000))
        assertNull(EndingOverlayLogic.active(listOf(SkipSegment("ed", 0, 40_000)), 10_000))
        assertEquals(ending, EndingOverlayLogic.active(segments, 1330_000))
        assertNull(EndingOverlayLogic.active(segments, 1326_669 + 90_000))
    }

    @Test
    fun `segments url gets duration in seconds`() {
        assertEquals(
            "https://site/api/player/skip-times?malId=1&episode=2&duration=1508.179",
            EndingOverlayLogic.segmentsUrl("https://site/api/player/skip-times?malId=1&episode=2", 1_508_179)
        )
        assertEquals(
            "https://site/skip?duration=10.5",
            EndingOverlayLogic.segmentsUrl("https://site/skip", 10_500)
        )
    }

    @Test
    fun `site response is parsed into milliseconds`() {
        val json = """{"ok":true,"data":[
            {"type":"op","startTime":71.5,"endTime":161.315},
            {"type":"ed","startTime":1326.669,"endTime":1508.179}
        ]}"""
        val segments = SkipSegmentsLogic.parse(json)
        assertEquals(2, segments.size)
        assertEquals(1326_669, segments[1].startMs)
        assertEquals(1508_179, segments[1].endMs)
        assertEquals("ed", segments[1].type)
    }

    @Test
    fun `broken response gives no segments`() {
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("not json"))
        assertEquals(emptyList<SkipSegment>(), SkipSegmentsLogic.parse("""{"ok":true,"data":[]}"""))
        assertEquals(
            emptyList<SkipSegment>(),
            SkipSegmentsLogic.parse("""{"ok":true,"data":[{"type":"ed","startTime":5,"endTime":5}]}""")
        )
    }
}
