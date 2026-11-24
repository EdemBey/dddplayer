package top.rootu.dddplayer.logic

import android.net.Uri
import android.util.Log
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.dddplayer.model.UrlQuality
import top.rootu.dddplayer.model.Voiceover
import java.util.concurrent.TimeUnit

/**
 * Resolves the quality list for one playlist item from the site API.
 *
 * Lampa Android forwards only URLs to the external player, so per-episode
 * quality maps cannot travel in the playlist itself. The plugin puts a small
 * descriptor (an API URL) per item into `X-DDD-Quality-Sources`; this loader
 * fetches it on demand and turns the response into a ready quality list.
 *
 * The selection rules mirror the plugin:
 *  - providers ordered by measured speed/reliability;
 *  - every signed URL is kept: some CDNs select the variant by the query
 *    signature while the path stays the same;
 *  - duplicate quality labels between providers are dropped, so the faster
 *    provider always wins.
 */
class UrlQualityLoader(private val client: OkHttpClient = defaultClient()) {

    companion object {
        private const val TAG = "DDDQuality"
        const val MAX_BODY_BYTES = 2L * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        // Same order as online_mod's providerPriority
        private val PROVIDER_PRIORITY = listOf(
            "dzen", "allvideo", "vk", "ok", "plvideo",
            "sibnet", "myvi", "ustore", "mail", "youtube"
        )

    }

    private val cache = object : LruCache<String, List<UrlQuality>>(32) {}

    /** Returns an empty list on any failure; callers keep the current stream. */
    fun load(descriptorUrl: String): List<UrlQuality> {
        if (descriptorUrl.isBlank()) return emptyList()

        cache.get(descriptorUrl)?.let { return it }

        val body = fetch(descriptorUrl) ?: return emptyList()

        val qualities = try {
            parse(descriptorUrl, body)
        } catch (e: Exception) {
            Log.d(TAG, "parse failed: ${e.message}")
            emptyList()
        }

        if (qualities.isNotEmpty()) cache.put(descriptorUrl, qualities)
        Log.d(TAG, "resolved ${qualities.size} qualities for $descriptorUrl")
        return qualities
    }

    // Тело ответа или null; используется и для сегментов пропуска (X-DDD-Segments)
    fun fetch(descriptorUrl: String): String? {
        return try {
            val request = Request.Builder().url(descriptorUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "HTTP ${response.code} for $descriptorUrl")
                    return null
                }
                // Ответ — небольшой JSON; ссылка на само видео по ошибке не должна читаться в память целиком
                if (response.body.contentLength() > MAX_BODY_BYTES) return null
                val body = response.peekBody(MAX_BODY_BYTES + 1)
                if (body.contentLength() > MAX_BODY_BYTES) null else body.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.message}")
            null
        }
    }

    /**
     * Озвучки серии по адресу из `X-DDD-Voiceover-Path`. Здесь не кэшируются:
     * список хранится в самом элементе. При любой ошибке возвращает пустой список,
     * тогда у серии просто нет выбора озвучки.
     */
    fun loadVoiceovers(descriptorUrl: String): List<Voiceover> {
        if (descriptorUrl.isBlank()) return emptyList()

        val body = fetch(descriptorUrl) ?: return emptyList()

        return try {
            parseVoiceovers(body)
        } catch (e: Exception) {
            Log.d(TAG, "voiceovers parse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * {"voiceovers":[{"id":"..","name":"..","selected":true,"url":"https://..",
     *   "qualities":[{"name":"1080p","url":"https://.."}]}]}
     * Записи без названия или без http(s)-ссылки пропускаются, без `id` им служит название.
     */
    internal fun parseVoiceovers(body: String): List<Voiceover> {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start == -1 || end <= start) return emptyList()

        val list = JSONObject(body.substring(start, end + 1)).optJSONArray("voiceovers") ?: return emptyList()
        val result = mutableListOf<Voiceover>()

        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val name = entry.optString("name")
            val url = entry.optString("url")
            if (name.isBlank() || !url.startsWith("http")) continue

            val qualities = mutableListOf<UrlQuality>()
            val array = entry.optJSONArray("qualities") ?: JSONArray()
            for (j in 0 until array.length()) {
                val quality = array.optJSONObject(j) ?: continue
                val qualityUrl = quality.optString("url")
                val qualityName = quality.optString("name")
                if (qualityName.isBlank() || !qualityUrl.startsWith("http")) continue
                qualities.add(UrlQuality(qualityName, qualityUrl))
            }

            result.add(
                Voiceover(
                    id = entry.optString("id").ifBlank { name },
                    name = name,
                    url = url,
                    selected = entry.optBoolean("selected"),
                    qualities = qualities
                )
            )
        }

        return result
    }

    internal fun parse(descriptorUrl: String, body: String): List<UrlQuality> {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start == -1 || end <= start) return emptyList()

        val root = JSONObject(body.substring(start, end + 1))
        val payload = root.optJSONObject("data") ?: root
        val sources = payload.optJSONArray("sources") ?: JSONArray()
        if (sources.length() == 0) return emptyList()

        val origin = originOf(descriptorUrl)
        val candidates = mutableListOf<Candidate>()

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val src = source.optString("src").ifBlank { source.optString("file") }
            if (src.isBlank()) continue

            val absolute = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("http://") || src.startsWith("https://") -> src
                src.startsWith("/") -> origin + src
                else -> "$origin/$src"
            }

            val quality = source.optString("quality").ifBlank { "auto" }
            candidates.add(
                Candidate(
                    name = quality,
                    url = absolute,
                    key = absolute,
                    rank = providerRank(source.optString("provider")),
                    weight = qualityWeight(quality)
                )
            )
        }

        candidates.sortWith(compareBy<Candidate> { it.rank }.thenByDescending { it.weight })

        val result = mutableListOf<UrlQuality>()
        val files = mutableSetOf<String>()
        val covered = mutableSetOf<String>()

        for (candidate in candidates) {
            if (!files.add(candidate.key)) continue
            if (!covered.add(candidate.name)) continue
            result.add(UrlQuality(candidate.name, candidate.url))
        }

        return result
    }

    private fun originOf(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: "https"
            val authority = uri.authority ?: return ""
            "$scheme://$authority"
        } catch (e: Exception) {
            ""
        }
    }

    private fun providerRank(provider: String): Int {
        val name = provider.lowercase()
        val index = PROVIDER_PRIORITY.indexOfFirst { it.isNotEmpty() && name.contains(it) }
        return if (index == -1) PROVIDER_PRIORITY.size else index
    }

    private fun qualityWeight(quality: String): Int {
        return quality.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private data class Candidate(
        val name: String,
        val url: String,
        val key: String,
        val rank: Int,
        val weight: Int
    )
}
