package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UrlQualityLoaderTest {

    private val loader = UrlQualityLoader()
    private val descriptor = "https://anime.example/api/player/based?source=44%3Atoken"

    private fun body(sources: String) = """
        {"ok":true,"data":{"sources":[$sources]}}
    """.trimIndent()

    @Test
    fun `keeps 1080p and takes real lower qualities from another provider`() {
        val json = body(
            """
            {"provider":"Dzen","quality":"1080p","src":"/api/player/proxy?src=https%3A%2F%2Fdzen%2Fv"},
            {"provider":"Dzen","quality":"720p","src":"/api/player/proxy?src=https%3A%2F%2Fdzen%2Fv%3Fsig%3D2"},
            {"provider":"Allvideo","quality":"1080p","src":"/api/player/proxy?src=https%3A%2F%2Fallvideo%2F1080"},
            {"provider":"Allvideo","quality":"720p","src":"/api/player/proxy?src=https%3A%2F%2Fallvideo%2F720"},
            {"provider":"Allvideo","quality":"360p","src":"/api/player/proxy?src=https%3A%2F%2Fallvideo%2F360"}
            """.trimIndent()
        )

        val qualities = loader.parse(descriptor, json)

        // Same Dzen file under different labels collapses to one entry,
        // 1080p stays first and lower qualities come from Allvideo.
        assertEquals(listOf("1080p", "720p", "360p"), qualities.map { it.name })
        assertTrue(qualities[0].url.contains("dzen"))
        assertTrue(qualities[1].url.contains("allvideo"))
        assertEquals(
            "https://anime.example/api/player/proxy?src=https%3A%2F%2Fallvideo%2F720",
            qualities[1].url
        )
    }

    @Test
    fun `relative sources are resolved against the descriptor origin`() {
        val json = body("""{"provider":"Mail","quality":"1080p","src":"/api/player/proxy?src=x"}""")
        val qualities = loader.parse(descriptor, json)

        assertEquals(1, qualities.size)
        assertEquals("https://anime.example/api/player/proxy?src=x", qualities.single().url)
    }

    @Test
    fun `garbage responses return an empty list`() {
        assertTrue(loader.parse(descriptor, "not json").isEmpty())
        assertTrue(loader.parse(descriptor, "").isEmpty())
        assertTrue(loader.parse(descriptor, body("")).isEmpty())
    }

    @Test
    fun `prefers faster provider at the same quality`() {
        val json = body(
            """
            {"provider":"Mail","quality":"1080p","src":"/api/player/proxy?src=https%3A%2F%2Fmail%2F1080"},
            {"provider":"Dzen","quality":"1080p","src":"/api/player/proxy?src=https%3A%2F%2Fdzen%2F1080"}
            """.trimIndent()
        )

        val qualities = loader.parse(descriptor, json)

        assertEquals(1, qualities.size)
        assertTrue(qualities.single().url.contains("dzen"))
    }
}
