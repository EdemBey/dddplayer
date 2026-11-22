package top.rootu.dddplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import top.rootu.dddplayer.logic.EndingOverlayLogic
import top.rootu.dddplayer.model.EndingOverlay
import top.rootu.dddplayer.model.MediaItem
import kotlin.math.abs

/**
 * Подмена звука на участке видео (X-DDD-Audio-Overlay): на эндинге основной
 * плеер глушится и поверх играет музыка, синхронно с позицией и паузой.
 *
 * Длительность видео известна только после загрузки, поэтому участки
 * запрашиваются тогда же; до этого звук не трогается.
 */
class AudioOverlay(
    private val context: Context,
    private val main: ExoPlayer,
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        const val TAG = "AudioOverlay"
        const val TICK_MS = 250L
        const val SYNC_TOLERANCE_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val overlays = mutableMapOf<String, EndingOverlay>()

    private var overlayPlayer: ExoPlayer? = null
    private var current: EndingOverlay? = null
    private var segments: List<EndingOverlayLogic.Segment> = emptyList()
    private var segmentsKey = ""
    private var loadingKey = ""
    private var mainMuted = false
    private var failed = false
    private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun setItems(items: List<MediaItem>) {
        overlays.clear()
        items.forEach { item -> item.endingOverlay?.let { overlays[item.uuid] = it } }
        reset()
        // Пересоздаем плеер музыки, чтобы не было наложения двух дорожек
        // при переходе на следующую серию.
        overlayPlayer?.release()
        overlayPlayer = null
        if (overlays.isNotEmpty() && !released) handler.postDelayed(ticker, TICK_MS)
    }

    fun release() {
        released = true
        handler.removeCallbacks(ticker)
        scope.cancel()
        reset()
        overlayPlayer?.release()
        overlayPlayer = null
    }

    private fun tick() {
        val item = main.currentMediaItem
        val overlay = item?.let { overlays[it.mediaId] }

        if (overlay !== current) {
            reset()
            current = overlay
        }
        if (overlay == null) return

        val duration = main.duration
        if (duration <= 0) return restore()

        val key = "${item.mediaId}:$duration"
        if (key != segmentsKey && key != loadingKey) loadSegments(overlay, key, duration)

        val segment = EndingOverlayLogic.active(segments, main.currentPosition)
        if (failed || segment == null) return restore()
        // Пауза, буферизация и конец видео: музыка тоже молчит.
        if (!main.playWhenReady || main.playbackState != Player.STATE_READY) return restore()

        val player = overlayPlayer ?: return restore()
        val offset = main.currentPosition - segment.startMs
        val ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
        // Музыка не готова или короче участка — не глушим видео.
        if (!ready || (player.duration > 0 && offset >= player.duration)) return restore()

        // Скорость воспроизведения должна совпадать с основным плеером,
        // иначе на 2x музыка отстает и тикер дергает seekTo -> пердит.
        val speed = main.playbackParameters.speed
        if (speed > 0 && abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.setPlaybackSpeed(speed)
        }
        if (player.playbackState == Player.STATE_ENDED || abs(player.currentPosition - offset) > SYNC_TOLERANCE_MS) {
            player.seekTo(offset)
        }
        player.playWhenReady = true
        // Глушим основной плеер и громкостью (мгновенно), и отключением
        // аудиодорожки (на случай passthrough, где volume=0 не работает).
        main.volume = 0f
        try {
            main.trackSelectionParameters = main.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        } catch (e: Exception) {}
        mainMuted = true
    }

    private fun restore() {
        if (overlayPlayer?.playWhenReady == true) overlayPlayer?.playWhenReady = false
        if (mainMuted) {
            // Возвращаем аудиодорожку основного плеера.
            main.volume = 1f
            try {
                main.trackSelectionParameters = main.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
            } catch (e: Exception) {}
            mainMuted = false
        }
    }

    private fun reset() {
        restore()
        segments = emptyList()
        segmentsKey = ""
        loadingKey = ""
        failed = false
    }

    private fun loadSegments(overlay: EndingOverlay, key: String, durationMs: Long) {
        loadingKey = key
        val url = EndingOverlayLogic.segmentsUrl(overlay.segmentsUrl, durationMs)

        scope.launch {
            val json = try {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body.string()
                }
            } catch (e: Exception) {
                Log.w(TAG, "skip-times failed: $url", e)
                return@launch
            }

            handler.post {
                if (released || key == segmentsKey) return@post
                loadingKey = ""
                segmentsKey = key
                segments = EndingOverlayLogic.parseSegments(json)
                failed = segments.isEmpty()
                prepareMusic(overlay)
            }
        }
    }

    private fun prepareMusic(overlay: EndingOverlay) {
        val player = overlayPlayer ?: createPlayer().also { overlayPlayer = it }

        val loaded = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (loaded == overlay.musicUrl && player.playbackState != Player.STATE_IDLE) return

        player.setMediaItem(Media3MediaItem.fromUri(overlay.musicUrl))
        player.prepare()
    }

    private fun createPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()
        // Свой звук без аудиофокуса: громкостью управляет основной плеер.
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        player.setHandleAudioBecomingNoisy(false)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "overlay music failed", error)
                failed = true
                restore()
            }
        })
        return player
    }
}
