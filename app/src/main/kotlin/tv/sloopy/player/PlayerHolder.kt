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
                    )
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    _state.value = _state.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
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

    fun play(url: String, isLive: Boolean, startPositionMs: Long = 0) {
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
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this stream (HTTP error). Often means the connection limit is reached."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Stream not found on the provider"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "The stream is not in a format this box can play"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This box has no decoder for the stream's video codec"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Fell behind the live stream"
        else -> "Playback failed (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
    }
}
