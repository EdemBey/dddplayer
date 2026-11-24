package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import top.rootu.dddplayer.model.SkipSegment

@RunWith(RobolectricTestRunner::class)
class IntentUtilsTest {

    private val context = RuntimeEnvironment.getApplication()

    private val videos = arrayOf(
        Uri.parse("http://example.com/e1.mp4"),
        Uri.parse("http://example.com/e2.mp4"),
        Uri.parse("http://example.com/e3.mp4")
    )

    @Test
    fun `single file with title, position as string, poster and headers`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
            putExtra("title", "Big Buck Bunny")
            putExtra("position", "60000")
            putExtra("thumbnail", "http://example.com/poster.jpg")
            putExtra("headers", arrayOf("Referer", "http://example.com/", "X-Token", "abc"))
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)
        val item = items.single()
        assertEquals(0, startIndex)
        assertEquals("Big Buck Bunny", item.title)
        assertEquals(60_000L, item.startPositionMs)
        assertEquals("http://example.com/poster.jpg", item.posterUri.toString())
        assertEquals(mapOf("Referer" to "http://example.com/", "X-Token" to "abc"), item.headers)
    }

    @Test
    fun `single file without title uses the file name`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/path/My%20Movie.mkv"))
        val (items, _) = IntentUtils.parseIntent(context, intent)
        assertEquals("My Movie.mkv", items.single().title)
        assertEquals(0L, items.single().startPositionMs)
    }

    @Test
    fun `playlist starts from the item matching the intent data`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videos[2], "video/*")
            putExtra("video_list", videos)
            putExtra("video_list.name", arrayOf("One", "Two", "Three"))
            putExtra("position", 5000L)
            // Data совпадает с элементом - start_index не используется
            putExtra("start_index", 1)
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)
        assertEquals(listOf("One", "Two", "Three"), items.map { it.title })
        assertEquals(2, startIndex)
        // Позиция применяется только к элементу из data
        assertEquals(listOf(0L, 0L, 5000L), items.map { it.startPositionMs })
    }

    @Test
    fun `start_index is a fallback and is clamped`() {
        val withIndex = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("start_index", 1)
        }
        assertEquals(1, IntentUtils.parseIntent(context, withIndex).second)

        val outOfRange = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("start_index", 10)
        }
        assertEquals(2, IntentUtils.parseIntent(context, outOfRange).second)

        val none = Intent(Intent.ACTION_VIEW).apply { putExtra("video_list", videos) }
        assertEquals(0, IntentUtils.parseIntent(context, none).second)
    }

    @Test
    fun `playlist titles fall back to file names`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("video_list.filename", arrayOf("first.mp4", "second.mp4", "third.mp4"))
        }
        val (items, _) = IntentUtils.parseIntent(context, intent)
        assertEquals(listOf("first.mp4", "second.mp4", "third.mp4"), items.map { it.title })
    }

    @Test
    fun `single file parses ddd_quality json array`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
            putExtra(
                "ddd_quality",
                """[{"name":"1080p","url":"http://example.com/1080.mp4"},{"name":"360p","url":"http://example.com/360.mp4"}]"""
            )
        }

        val item = IntentUtils.parseIntent(context, intent).first.single()

        assertEquals(2, item.urlQualities.size)
        assertEquals("1080p", item.urlQualities[0].name)
        assertEquals("http://example.com/1080.mp4", item.urlQualities[0].url)
        assertEquals("360p", item.urlQualities[1].name)
    }

    @Test
    fun `single file parses ddd_quality json object`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
            putExtra(
                "ddd_quality",
                """{"1080p":"http://example.com/1080.mp4","360p":"http://example.com/360.mp4"}"""
            )
        }

        val item = IntentUtils.parseIntent(context, intent).first.single()

        assertEquals(
            setOf("1080p" to "http://example.com/1080.mp4", "360p" to "http://example.com/360.mp4"),
            item.urlQualities.map { it.name to it.url }.toSet()
        )
    }

    @Test
    fun `single file reads ddd_quality from the X-DDD-Quality header`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Quality",
                    """[{"name":"1080p","url":"http://example.com/1080.mp4"},{"name":"360p","url":"http://example.com/360.mp4"}]""",
                    "Referer",
                    "http://example.com/"
                )
            )
        }

        val item = IntentUtils.parseIntent(context, intent).first.single()

        assertEquals(2, item.urlQualities.size)
        assertEquals("1080p", item.urlQualities[0].name)
        assertEquals(mapOf("Referer" to "http://example.com/"), item.headers)
    }

    @Test
    fun `playlist applies ddd_quality only to the start item`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videos[1], "video/*")
            putExtra("video_list", videos)
            putExtra(
                "ddd_quality",
                """[{"name":"1080p","url":"http://example.com/1080.mp4"},{"name":"360p","url":"http://example.com/360.mp4"}]"""
            )
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)

        assertEquals(1, startIndex)
        assertEquals(2, items[startIndex].urlQualities.size)
        assertEquals(0, items[0].urlQualities.size)
        assertEquals(0, items[2].urlQualities.size)
    }

    @Test
    fun `playlist quality sources header is parsed per item and stripped`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videos[1], "video/*")
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "Referer", "http://example.com/",
                    "X-DDD-Quality-Sources",
                    """{"0":"http://example.com/based?source=44%3Aa","2":"http://example.com/based?source=44%3Ac"}""",
                    "X-DDD-Quality",
                    """[{"name":"1080p","url":"http://example.com/1080.mp4"}]"""
                )
            )
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)

        assertEquals(1, startIndex)
        assertEquals("http://example.com/based?source=44%3Aa", items[0].qualitySourceUrl)
        assertEquals(null, items[1].qualitySourceUrl)
        assertEquals("http://example.com/based?source=44%3Ac", items[2].qualitySourceUrl)

        // Service headers must not leak to the video CDN
        items.forEach { item ->
            assertTrue(item.headers.keys.none { it.equals("X-DDD-Quality", true) })
            assertTrue(item.headers.keys.none { it.equals("X-DDD-Quality-Sources", true) })
            assertEquals("http://example.com/", item.headers["Referer"])
        }
    }

    @Test
    fun inlineQualitySourcesFillNextEpisodes() {
        val videos = arrayListOf(
            Uri.parse("http://example.com/e1.mp4"),
            Uri.parse("http://example.com/e2.mp4"),
            Uri.parse("http://example.com/e3.mp4")
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = videos[0]
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Quality-Sources",
                    """{"1":[{"name":"720p","url":"http://example.com/e2-720.mp4"},{"name":"1080p","url":"http://example.com/e2.mp4"}],"2":"http://example.com/based?source=x"}"""
                )
            )
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        assertEquals(listOf("720p", "1080p"), items[1].urlQualities.map { it.name })
        assertEquals(null, items[1].qualitySourceUrl)
        assertEquals("http://example.com/based?source=x", items[2].qualitySourceUrl)
        assertTrue(items[2].urlQualities.isEmpty())
        assertTrue(items[1].headers.keys.none { it.equals("X-DDD-Quality-Sources", true) })
    }

    @Test
    fun itemKeysAndPositionsAreParsedAndStripped() {
        val videos = arrayListOf(
            Uri.parse("http://example.com/e1.mp4"),
            Uri.parse("http://example.com/e2.mp4")
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = videos[0]
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Item-Keys", """{"0":"rezka:1:56:1:1","1":"rezka:1:56:1:2"}""",
                    "X-DDD-Positions", """{"1":734000}"""
                )
            )
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        assertEquals("rezka:1:56:1:1", items[0].resumeKey)
        assertEquals("rezka:1:56:1:2", items[1].resumeKey)
        assertEquals(0L, items[0].knownPositionMs)
        assertEquals(734000L, items[1].knownPositionMs)
        items.forEach { item ->
            assertTrue(item.headers.keys.none { it.startsWith("X-DDD-", true) })
        }
    }

    @Test
    fun `audio overlay header is parsed per item and not sent to the server`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = videos[0]
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Audio-Overlay",
                    """{"0":{"music":"http://example.com/ed.m4a","segments":"http://example.com/skip?malId=1&episode=1"},"1":{"music":"http://example.com/ed.m4a"}}"""
                )
            )
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        assertEquals("http://example.com/ed.m4a", items[0].endingOverlay?.musicUrl)
        assertEquals("http://example.com/skip?malId=1&episode=1", items[0].endingOverlay?.segmentsUrl)
        // Без адреса участков подменять нечего
        assertEquals(null, items[1].endingOverlay)
        items.forEach { item ->
            assertTrue(item.headers.keys.none { it.startsWith("X-DDD-", true) })
        }
    }

    @Test
    fun `skip segments header gives items segments or an address and is not sent to the server`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = videos[0]
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Segments",
                    """{"0":[{"type":"op","startTime":0,"endTime":89}],"1":"https://api.aniskip.com/v2/skip-times/20/2?episodeLength=0","2":"ftp://x"}"""
                )
            )
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        assertEquals(listOf(SkipSegment("op", 0, 89_000)), items[0].skipSegments)
        assertEquals(null, items[0].skipSegmentsUrl)
        assertEquals(emptyList<SkipSegment>(), items[1].skipSegments)
        assertEquals("https://api.aniskip.com/v2/skip-times/20/2?episodeLength=0", items[1].skipSegmentsUrl)
        // Не http(s) — не качаем
        assertEquals(null, items[2].skipSegmentsUrl)
        items.forEach { item ->
            assertTrue(item.headers.keys.none { it.startsWith("X-DDD-", true) })
        }
    }

    @Test
    fun `skip segments object form gives items both segments and an address`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = videos[0]
            putExtra("video_list", videos)
            putExtra(
                "headers",
                arrayOf(
                    "X-DDD-Segments",
                    """{"0":{"segments":[{"type":"ed","startTime":904,"endTime":960}],"url":"https://x/skip?duration=0"},"1":{"url":"https://x/s2"},"2":{"segments":[{"type":"op","startTime":0,"endTime":89}],"url":"ftp://x"}}"""
                )
            )
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        assertEquals(listOf(SkipSegment("ed", 904_000, 960_000)), items[0].skipSegments)
        assertEquals("https://x/skip?duration=0", items[0].skipSegmentsUrl)
        assertEquals(emptyList<SkipSegment>(), items[1].skipSegments)
        assertEquals("https://x/s2", items[1].skipSegmentsUrl)
        assertEquals(listOf(SkipSegment("op", 0, 89_000)), items[2].skipSegments)
        assertEquals(null, items[2].skipSegmentsUrl)
    }

    @Test
    fun `voiceover path header gives every item its own address and is not sent to the server`() {
        val streams = arrayOf(
            Uri.parse("https://helper.example/play.m3u8?q=1080&url=https%3A%2F%2Fsite%2F%3Fepisode%3D1%26audio%3DA%2520B"),
            Uri.parse("https://helper.example/stream.m3u8?url=https%3A%2F%2Fsite%2F%3Fepisode%3D2")
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = streams[1]
            putExtra("video_list", streams)
            putExtra("headers", arrayOf("X-DDD-Voiceover-Path", "/voiceovers"))
        }

        val (items, _) = IntentUtils.parseIntent(context, intent)

        // Меняется только путь: query с закодированной ссылкой остается как был
        assertEquals(
            "https://helper.example/voiceovers?q=1080&url=https%3A%2F%2Fsite%2F%3Fepisode%3D1%26audio%3DA%2520B",
            items[0].voiceoverSourceUrl
        )
        assertEquals(
            "https://helper.example/voiceovers?url=https%3A%2F%2Fsite%2F%3Fepisode%3D2",
            items[1].voiceoverSourceUrl
        )
        items.forEach { item ->
            assertTrue(item.headers.keys.none { it.startsWith("X-DDD-", true) })
        }
    }

    @Test
    fun `single file takes its voiceover address from the path header`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/dir/video.m3u8?token=a%2Fb"), "video/*")
            putExtra("headers", arrayOf("X-DDD-Voiceover-Path", "/api/voiceovers"))
        }

        val item = IntentUtils.parseIntent(context, intent).first.single()

        assertEquals("http://example.com/api/voiceovers?token=a%2Fb", item.voiceoverSourceUrl)
        assertTrue(item.headers.isEmpty())
    }

    @Test
    fun `voiceover address is not built without the header, for a bad path or a local file`() {
        fun source(uri: String, vararg headers: String): String? {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/*")
                if (headers.isNotEmpty()) putExtra("headers", arrayOf(*headers))
            }
            return IntentUtils.parseIntent(context, intent).first.single().voiceoverSourceUrl
        }

        assertEquals(null, source("http://example.com/video.mp4"))
        assertEquals(null, source("http://example.com/video.mp4", "X-DDD-Voiceover-Path", "voiceovers"))
        assertEquals(null, source("http://example.com/video.mp4", "X-DDD-Voiceover-Path", ""))
        assertEquals(null, source("file:///sdcard/video.mp4", "X-DDD-Voiceover-Path", "/voiceovers"))
    }
}
