package tv.sloopy.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import tv.sloopy.record.Smb
import java.io.IOException

/** Plays a recording that lives on an SMB share: positioned reads straight from the file. */
@UnstableApi
class SmbDataSource(private val context: Context) : BaseDataSource(true) {
    private var handle: Smb.Handle? = null
    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (h, f) = try { Smb.openInput(context, dataSpec.uri.toString()) } catch (e: Exception) { throw IOException("Could not open the recording on the share", e) }
        handle = h; file = f
        val size = f.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        if (remaining < 0) { close(); throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = file!!.read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }; file = null
        runCatching { handle?.close() }; handle = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    /** Sends `smb://` to [SmbDataSource] and everything else to the usual source. */
    class Routing(private val base: DataSource, private val smb: DataSource) : DataSource {
        private var active: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) { base.addTransferListener(transferListener); smb.addTransferListener(transferListener) }
        override fun open(dataSpec: DataSpec): Long { val d = if (dataSpec.uri.scheme == "smb") smb else base; active = d; return d.open(dataSpec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
    }

    class Factory(private val context: Context, private val base: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Routing(base.createDataSource(), SmbDataSource(context))
    }
}
