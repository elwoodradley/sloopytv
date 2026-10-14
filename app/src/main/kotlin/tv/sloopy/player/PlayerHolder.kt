package tv.sloopy.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.sloopy.TLog
import tv.sloopy.core.Redact
import tv.sloopy.data.AndroidHttp

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isLive: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** e.g. "1080p · 50 fps · H.264 · AAC 2.0", once the decoders have started. */
    val quality: String? = null,
    /** Frames per second of the video being decoded, 0 until known. Drives refresh-rate matching. */
    val frameRate: Float = 0f,
    /** Playback reached the end of a film or episode. */
    val ended: Boolean = false,
)

/**
 * One ExoPlayer for the whole app. Live TV uses MPEG-TS over HTTP or HLS; VOD is plain
 * progressive files. The data source is the shared OkHttp client, so the same scheme guard
 * and user agent apply, and nothing is ever logged.
 */
@UnstableApi
class PlayerHolder(context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var retries = 0
    private var currentUrl: String? = null

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setRenderersFactory(
            DefaultRenderersFactory(context.applicationContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context.applicationContext)
                .setDataSourceFactory(
                    OkHttpDataSource.Factory(AndroidHttp.client).setUserAgent(AndroidHttp.USER_AGENT)
                )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000, 45_000, 1_200, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            // Subtitles off until the viewer turns them on from the track menu.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying, error = if (isPlaying) null else _state.value.error, reconnecting = if (isPlaying) false else _state.value.reconnecting)
                    if (isPlaying) retries = 0
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = _state.value.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = if (duration == C.TIME_UNSET) 0 else duration,
                        ended = playbackState == Player.STATE_ENDED,
                    )
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    _state.value = _state.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height, quality = describe(videoSize.width, videoSize.height))
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    val s = _state.value
                    val q = describe(s.videoWidth, s.videoHeight) ?: s.quality   // early track events carry no formats yet
                    TLog.d { "stream quality: $q" }
                    val fps = player.videoFormat?.frameRate ?: 0f
                    _state.value = s.copy(quality = q, frameRate = if (fps > 0f) fps else s.frameRate)
                }
                override fun onPlayerError(error: PlaybackException) {
                    TLog.w { "player error ${error.errorCodeName}: ${Redact.apply(error.message)}" }
                    val url = currentUrl
                    if (url != null && retries < 3 && error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                        retries++
                        _state.value = _state.value.copy(reconnecting = true)
                        seekToDefaultPosition()
                        prepare()
                        play()
                        return
                    }
                    _state.value = _state.value.copy(error = friendly(error), isPlaying = false, isBuffering = false, reconnecting = false)
                }
            })
        }

    /** Human line for the badge from the formats the player is actually decoding. */
    private fun describe(w: Int, h: Int): String? {
        val v = player.videoFormat
        val a = player.audioFormat
        if (v == null && a == null && h <= 0) return null
        val parts = ArrayList<String>()
        val height = if (h > 0) h else v?.height ?: 0
        if (height > 0) parts.add(
            when {
                height >= 2000 -> "4K"
                height >= 1000 -> "1080p"
                height >= 700 -> "720p"
                height >= 500 -> "576p"
                else -> "${height}p"
            }
        )
        val fps = v?.frameRate ?: 0f
        if (fps > 0f) parts.add(if (fps % 1f == 0f) "${fps.toInt()} fps" else "%.2f fps".format(fps))
        codecName(v?.sampleMimeType)?.let { parts.add(it) }
        val audio = codecName(a?.sampleMimeType)
        if (audio != null) {
            val ch = a?.channelCount ?: 0
            parts.add(audio + when (ch) { 1 -> " mono"; 2 -> " 2.0"; 6 -> " 5.1"; 8 -> " 7.1"; else -> "" })
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        androidx.media3.common.MimeTypes.VIDEO_H264 -> "H.264"
        androidx.media3.common.MimeTypes.VIDEO_H265 -> "H.265"
        androidx.media3.common.MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        androidx.media3.common.MimeTypes.VIDEO_VP9 -> "VP9"
        androidx.media3.common.MimeTypes.VIDEO_AV1 -> "AV1"
        androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
        androidx.media3.common.MimeTypes.AUDIO_AC3 -> "AC-3"
        androidx.media3.common.MimeTypes.AUDIO_E_AC3, androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        androidx.media3.common.MimeTypes.AUDIO_MPEG, androidx.media3.common.MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        androidx.media3.common.MimeTypes.AUDIO_DTS -> "DTS"
        androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
        else -> mime.substringAfter('/').uppercase().take(8)
    }

    fun play(url: String, isLive: Boolean, startPositionMs: Long = 0) {
        _state.value = _state.value.copy(frameRate = 0f)
        currentUrl = url
        retries = 0
        _state.value = PlayerState(isLive = isLive, isBuffering = true)
        val item = MediaItem.Builder().setUri(url).apply {
            if (isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).build())
        }.build()
        player.setMediaItem(item, if (isLive || startPositionMs <= 0) C.TIME_UNSET else startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        currentUrl = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState()
    }

    fun position(): Long = player.currentPosition
    fun duration(): Long = if (player.duration == C.TIME_UNSET) 0 else player.duration

    fun release() {
        player.release()
    }

    private fun friendly(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection to the stream"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            "The provider refused this stream" + (if (code != null) " (HTTP $code)" else "") + ". Often the connection limit, or the account."
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Stream not found on the provider"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "The stream is not in a format this box can play"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This box has no decoder for the stream's video codec"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Fell behind the live stream"
        else -> "Playback failed (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
    }
}
