package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

@RunWith(RobolectricTestRunner::class)
class UrlQualityLoaderTest {

    private val loader = UrlQualityLoader()
    private val descriptor = "https://anime.example/api/player/based?source=44%3Atoken"

    private fun body(sources: String) = """
        {"ok":true,"data":{"sources":[$sources]}}
    """.trimIndent()

    @Test
    fun `keeps dzen variants and skips labels another provider already covers`() {
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

        // Обе подписи Dzen — разные URL, поэтому остаются обе.
        // Allvideo даёт только не занятую 360p.
        assertEquals(listOf("1080p", "720p", "360p"), qualities.map { it.name })
        assertTrue(qualities[0].url.contains("dzen"))
        assertTrue(qualities[1].url.contains("dzen"))
        assertEquals(
            "https://anime.example/api/player/proxy?src=https%3A%2F%2Fallvideo%2F360",
            qualities[2].url
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

    @Test
    fun `voiceovers keep their own stream and quality list`() {
        val json = """
            {"voiceovers":[
              {"id":"(English)","name":"(English) 9","selected":true,"url":"https://h.example/stream?a=en",
               "qualities":[{"name":"1080p","url":"https://h.example/play?q=1080&a=en"},{"name":"720p","url":"https://h.example/play?q=720&a=en"},{"name":"480p"}]},
              {"name":"LostFilm","url":"https://h.example/stream?a=lf"},
              {"name":"","url":"https://h.example/unnamed"},
              {"name":"No url"}
            ]}
        """.trimIndent()

        val voiceovers = loader.parseVoiceovers(json)

        assertEquals(listOf("(English) 9", "LostFilm"), voiceovers.map { it.name })
        assertEquals("(English)", voiceovers[0].id)
        assertTrue(voiceovers[0].selected)
        assertEquals(listOf("1080p", "720p"), voiceovers[0].qualities.map { it.name })
        // Без id озвучка опознается по названию
        assertEquals("LostFilm", voiceovers[1].id)
        assertTrue(!voiceovers[1].selected)
        assertTrue(voiceovers[1].qualities.isEmpty())
    }

    @Test
    fun `garbage voiceover responses return an empty list`() {
        assertTrue(loader.parseVoiceovers("not json").isEmpty())
        assertTrue(loader.parseVoiceovers("").isEmpty())
        assertTrue(loader.parseVoiceovers("""{"voiceovers":[]}""").isEmpty())
        assertTrue(loader.parseVoiceovers("""{"ok":true}""").isEmpty())
    }

    @Test
    fun `fetch refuses bodies larger than the limit`() {
        fun loaderAnswering(body: ResponseBody) = UrlQualityLoader(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body).build()
        }.build())
        val big = "x".repeat((UrlQualityLoader.MAX_BODY_BYTES + 1).toInt())
        assertEquals("{}", loaderAnswering("{}".toResponseBody()).fetch("https://x/a"))
        // Ссылка на видео по ошибке: не читаем гигабайты в память — ни с известной длиной, ни без нее (chunked)
        assertNull(loaderAnswering(big.toResponseBody()).fetch("https://x/b"))
        assertNull(loaderAnswering(Buffer().writeUtf8(big).asResponseBody(null, -1)).fetch("https://x/c"))
        assertEquals("{}", loaderAnswering(Buffer().writeUtf8("{}").asResponseBody(null, -1)).fetch("https://x/d"))
    }
}
