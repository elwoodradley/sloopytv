package tv.sloopy.core.net

import java.io.IOException
import java.io.InputStream

/**
 * Minimal HTTP abstraction so the core module stays free of platform networking.
 * The Android app implements this with OkHttp; a desktop build can use the JDK client.
 */
interface Http {
    /**
     * Performs a GET. The returned stream must be closed by the caller.
     * Implementations must enforce [timeoutMs] on connect and read, must not follow
     * redirects to a different scheme (http -> file, etc.), and must cap redirects.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000): HttpResponse
}

class HttpResponse(
    val code: Int,
    val contentType: String?,
    val contentLength: Long,
    val body: InputStream,
) : AutoCloseable {
    val ok: Boolean get() = code in 200..299
    override fun close() = body.close()
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Thrown when a response body exceeds the byte cap the caller set. */
class SizeLimitExceeded(val limit: Long) : IOException("Response exceeded $limit bytes")

/**
 * Hard byte cap on an input stream. Applied after any decompression so that a gzip bomb
 * cannot blow past it.
 */
class LimitedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L

    private fun bump(n: Int) {
        if (n > 0) {
            count += n
            if (count > limit) throw SizeLimitExceeded(limit)
        }
    }

    override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { bump(it) }
    override fun available(): Int = inner.available()
    override fun close() = inner.close()
}

object Limits {
    const val JSON_BYTES = 64L * 1024 * 1024        // catalogue responses from big providers run to tens of MB
    const val XMLTV_BYTES = 512L * 1024 * 1024      // a 7 day guide for 10k channels, decompressed
    const val M3U_BYTES = 64L * 1024 * 1024
    const val MAX_TEXT = 4_000                       // any single title / description
    const val MAX_PROGRAMMES = 3_000_000
    const val MAX_CHANNELS = 200_000
}

/** Reads a whole response into memory, capped. Use only for JSON sized bodies. */
fun HttpResponse.readAllCapped(limit: Long): ByteArray {
    if (contentLength > limit) throw SizeLimitExceeded(limit)
    return LimitedInputStream(body, limit).use { it.readBytes() }
}
