package tv.sloopy.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TeeDataSource
import java.io.OutputStream

/**
 * Lets a recording ride the stream the player is already pulling, so recording the channel
 * you are watching costs no second provider connection. The player's data source factory
 * wraps every open of [playingUrl] in a tee while a sink is attached.
 */
@UnstableApi
object RecordTee {
    @Volatile var playingUrl: String? = null
    @Volatile private var sink: OutputStream? = null
    @Volatile var recordingId: Long = -1
    @Volatile var bytes: Long = 0
    @Volatile var stopEpoch: Long = 0

    val active: Boolean get() = sink != null

    fun attach(id: Long, out: OutputStream, stop: Long) { recordingId = id; bytes = 0; stopEpoch = stop; sink = out }
    fun detach(): Long { val s = sink; sink = null; recordingId = -1; runCatching { s?.flush() }; return bytes }

    /** Wraps [upstream] so that opens of the playing URL also write to the sink. */
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = TeeDataSource(upstream.createDataSource(), Sink())
    }

    private class Sink : DataSink {
        private var on = false
        override fun open(dataSpec: DataSpec) { on = sink != null && dataSpec.uri.toString() == playingUrl && System.currentTimeMillis() / 1000 < stopEpoch }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (!on) return
            val s = sink ?: return
            try { s.write(buffer, offset, length); bytes += length } catch (e: Exception) { on = false }
        }
        override fun close() { on = false }
    }
}
