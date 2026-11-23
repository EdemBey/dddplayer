package top.rootu.dddplayer.model

import android.net.Uri
import java.util.UUID

data class MediaItem(
    val uri: Uri,
    val title: String? = null,
    val filename: String? = null,
    val posterUri: Uri? = null,
    val group: String? = null, // Группа (group-title) из M3U
    val absoluteIndex: Int = 0, // Позиция в полном плейлисте (для номера при фильтрации по группе)
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val startPositionMs: Long = 0,
    val stereoType: StereoInputType = StereoInputType.NONE,
    // Альтернативные URL одного видео (Lampa quality map / DDD intent extra)
    val urlQualities: List<UrlQuality> = emptyList(),
    // Адрес, из которого можно получить качества для этой серии (Naruto Based based API)
    val qualitySourceUrl: String? = null,
    // Адрес, из которого можно получить озвучки этой серии (X-DDD-Voiceover-Path)
    val voiceoverSourceUrl: String? = null,
    // Озвучки серии: у каждой свой поток и свои качества
    val voiceovers: List<Voiceover> = emptyList(),
    // Стабильный ключ серии от приложения (X-DDD-Item-Keys): позиция и настройки
    // хранятся по нему, а не по URL, если ссылки подписаны и меняются
    val resumeKey: String? = null,
    // Позиция, известная приложению (X-DDD-Positions), если своей сохраненной нет
    val knownPositionMs: Long = 0,
    // Подмена звука на участке видео (X-DDD-Audio-Overlay)
    val endingOverlay: EndingOverlay? = null,
    // Уникальный ID для DiffUtil, генерируется автоматически при создании объекта
    val uuid: String = UUID.randomUUID().toString()
)

/**
 * Музыка на участок видео: приложение отдает ссылку на аудио и адрес,
 * который по длительности видео возвращает участки (X-DDD-Audio-Overlay).
 */
data class EndingOverlay(
    val musicUrl: String,
    val segmentsUrl: String
)

data class UrlQuality(
    val name: String,
    val url: String
)

/**
 * Озвучка отдельным потоком того же видео: звук вшит в видео, дорожкой ее не переключить.
 * id не меняется от серии к серии, по нему выбор переносится на следующие серии.
 */
data class Voiceover(
    val id: String,
    val name: String,
    val url: String,
    val selected: Boolean = false,
    val qualities: List<UrlQuality> = emptyList()
)

data class SubtitleItem(
    val uri: Uri,
    val name: String? = null,
    val filename: String? = null,
    val mimeType: String? = null
)