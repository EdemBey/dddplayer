package top.rootu.dddplayer.logic

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val description: String,
    val downloadUrl: String,
    val size: Long
)

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient()
    private val repoUrl = "https://api.github.com/repos/$REPO/releases"

    /**
     * Возвращает информацию о более новом релизе или null, если обновлений нет.
     * Сетевые ошибки и ошибки разбора пробрасываются, чтобы вызывающий код
     * мог отличить "нет обновлений" от "не удалось проверить".
     */
    suspend fun checkForUpdates(currentVersionName: String?): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(repoUrl).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }

        val releases = JSONArray(json)
        // Берем первый опубликованный стабильный релиз (без черновиков и pre-release)
        val latest = (0 until releases.length())
            .map { releases.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            ?: return@withContext null

        val tagName = latest.getString("tag_name") // e.g. "v1.0.1"
        if (currentVersionName != null && !isNewer(tagName, currentVersionName)) {
            return@withContext null
        }

        val apkAsset = apkAsset(latest.getJSONArray("assets")) ?: return@withContext null

        UpdateInfo(
            version = tagName,
            description = latest.optString("body", "").ifEmpty { "Список изменений не предоставлен." },
            downloadUrl = apkAsset.getString("browser_download_url"),
            size = apkAsset.getLong("size")
        )
    }

    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val totalLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        val data = ByteArray(16 * 1024)
                        var count: Int
                        var total: Long = 0
                        var lastProgress = -1

                        while (inputStream.read(data).also { count = it } != -1) {
                            total += count
                            outputStream.write(data, 0, count)
                            if (totalLength > 0) {
                                val progress = ((total * 100) / totalLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                if (totalLength > 0 && file.length() != totalLength) {
                    throw IOException("Incomplete download: ${file.length()} of $totalLength")
                }
            }
            return@withContext file
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete() // Не оставляем битый или чужой (HTML ошибки) файл
            return@withContext null
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun deleteUpdateFile() {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) {
            file.delete()
        }
    }

    fun toJson(info: UpdateInfo): String {
        val json = JSONObject()
        json.put("version", info.version)
        json.put("description", info.description)
        json.put("downloadUrl", info.downloadUrl)
        json.put("size", info.size)
        return json.toString()
    }

    fun fromJson(jsonStr: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonStr)
            UpdateInfo(
                json.getString("version"),
                json.getString("description"),
                json.getString("downloadUrl"),
                json.getLong("size")
            )
        } catch (_: Exception) { null }
    }

    /**
     * Сравнивает тег релиза с текущей версией сборки (git describe, без префикса "v").
     * Сборка с этого же тега ("tag", "tag-5-gabcdef", "tag-dirty") обновлением не считается.
     * Числовые теги сравниваются по компонентам, чтобы не предлагать откат.
     * Именные теги (например, "jojo-adhd-edition") считаются новее, если отличаются от текущего.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val tag = remote.trim().removePrefix("v")
        val cur = current.trim().removePrefix("v").replace(DESCRIBE_SUFFIX, "")
        if (cur == tag) return false
        if (!NUMERIC.matches(tag) || !NUMERIC.matches(cur)) return true
        val r = parseVersion(tag) ?: return true
        val c = parseVersion(cur) ?: return true
        for (i in 0 until maxOf(r.size, c.size)) {
            val diff = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Устанавливать можно только .apk: релиз без него обновлением не считается. */
    fun apkAsset(assets: JSONArray): JSONObject? =
        (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }

    /** Кэш мог остаться от оригинального репозитория: принимаем только свои релизы. */
    fun isOwnRelease(info: UpdateInfo): Boolean =
        info.downloadUrl.startsWith("https://github.com/$REPO/")

    private fun parseVersion(version: String): List<Int>? {
        val match = VERSION_REGEX.find(version.trim().removePrefix("v")) ?: return null
        return match.value.split('.').map { it.toIntOrNull() ?: 0 }
    }

    private companion object {
        const val REPO = "EdemBey/dddplayer"
        val NUMERIC = Regex("""^\d+(\.\d+)*$""")
        val DESCRIBE_SUFFIX = Regex("""(-\d+-g[0-9a-f]+)?(-dirty)?$""")
        val VERSION_REGEX = Regex("""^\d+(\.\d+)*""")
    }
}