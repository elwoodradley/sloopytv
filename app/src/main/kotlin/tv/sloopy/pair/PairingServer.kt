package tv.sloopy.pair

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tv.sloopy.TLog
import tv.sloopy.core.crypto.PairKey
import tv.sloopy.core.crypto.SecretBox
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import tv.sloopy.core.util.B64

/** What the phone sent. Secrets inside; never log it. */
class PairPayload(val name: String, val url: String, val user: String, val pass: String)

/**
 * A tiny web server on the home network so a provider login can be typed on a phone instead
 * of with the remote. One session per screen visit:
 *
 *  - The TV shows a QR code with `http://<ip>:<port>/#<code>.<key>`. The key lives in the URL
 *    fragment, which browsers never send to the server, so the page can encrypt with it and
 *    nothing on the Wi-Fi sees the password in clear.
 *  - Typing the address by hand instead gives no key; the page then derives one from the
 *    six-digit code and a per-session salt (PairKey). Weaker, still not plaintext.
 *  - Both paths use NaCl secretbox (tweetnacl-js in the page, SecretBox on the TV).
 *  - Six wrong codes end the session. The session expires after ten minutes.
 */
class PairingServer(private val context: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {
    private val rnd = SecureRandom()
    val code: String = "%06d".format(rnd.nextInt(1_000_000))
    private val sessionKey = ByteArray(32).also { rnd.nextBytes(it) }
    private val salt = ByteArray(16).also { rnd.nextBytes(it) }
    private val derivedKey: ByteArray by lazy { PairKey.derive(code, salt) }
    private val startedAt = System.currentTimeMillis()
    @Volatile private var attempts = 0
    @Volatile private var done = false

    private val _received = MutableStateFlow<PairPayload?>(null)
    val received: StateFlow<PairPayload?> = _received

    val address: String get() = "http://${localIp() ?: "?"}:$listeningPort/"
    val qrText: String get() = address + "#" + code + "." + b64url(sessionKey)

    private val json = Json { ignoreUnknownKeys = true }
    private val naclJs: ByteArray by lazy { context.assets.open("pair/nacl-fast.min.js").use { it.readBytes() } }

    override fun serve(session: IHTTPSession): Response {
        val expired = System.currentTimeMillis() - startedAt > 10 * 60_000
        return try {
            when {
                session.uri == "/nacl.js" -> newFixedLengthResponse(Response.Status.OK, "application/javascript", naclJs.inputStream(), naclJs.size.toLong())
                session.method == Method.POST && session.uri == "/pair" -> handlePair(session, expired)
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PairPage.html(saltHex = salt.hex(), expired = expired || done))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        } catch (e: Exception) {
            TLog.w { "pair request failed: ${TLog.userMessage(e)}" }
            jsonResponse(Response.Status.INTERNAL_ERROR, false, "Something went wrong on the TV")
        }.also { it.addHeader("Cache-Control", "no-store") }
    }

    private fun handlePair(session: IHTTPSession, expired: Boolean): Response {
        if (done) return jsonResponse(Response.Status.GONE, false, "Already paired. Close this page.")
        if (expired) return jsonResponse(Response.Status.GONE, false, "This code has expired. Reopen the screen on the TV.")
        if (attempts >= 6) return jsonResponse(Response.Status.TOO_MANY_REQUESTS, false, "Too many wrong codes. Reopen the screen on the TV.")
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 16_384) return jsonResponse(Response.Status.BAD_REQUEST, false, "Bad request")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) { val n = session.inputStream.read(buf, read, len - read); if (n < 0) break; read += n }
        val body = json.parseToJsonElement(buf.decodeToString()) as? JsonObject ?: return jsonResponse(Response.Status.BAD_REQUEST, false, "Bad request")
        val sentCode = body.str("code") ?: ""
        val mode = body.str("mode") ?: "code"
        val nonce = runCatching { B64.decode(body.str("nonce") ?: "") }.getOrNull()
        val box = runCatching { B64.decode(body.str("box") ?: "") }.getOrNull()
        if (nonce == null || box == null || nonce.size != SecretBox.NONCE_BYTES) return jsonResponse(Response.Status.BAD_REQUEST, false, "Bad request")

        if (!constantTimeEquals(sentCode, code)) {
            attempts++
            return jsonResponse(Response.Status.FORBIDDEN, false, "That code doesn't match the TV.")
        }
        val key = if (mode == "qr") sessionKey else derivedKey
        val plain = SecretBox.open(box, nonce, key) ?: run {
            attempts++
            return jsonResponse(Response.Status.FORBIDDEN, false, "Couldn't decrypt. Rescan the QR code or retype the code.")
        }
        val payload = runCatching { json.parseToJsonElement(plain.decodeToString()) as? JsonObject }.getOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, false, "Bad request")
        val url = payload.str("url")?.trim().orEmpty()
        if (url.isEmpty()) return jsonResponse(Response.Status.BAD_REQUEST, false, "The server address is empty.")
        done = true
        _received.value = PairPayload(
            name = payload.str("name")?.trim().orEmpty().take(60),
            url = url.take(2048),
            user = payload.str("user")?.trim().orEmpty().take(256),
            pass = payload.str("pass")?.trim().orEmpty().take(256),
        )
        return jsonResponse(Response.Status.OK, true, "Sent to the TV. You can close this page.")
    }

    private fun jsonResponse(status: Response.Status, ok: Boolean, message: String): Response {
        val body = """{"ok":$ok,"message":${JsonPrimitive(message)}}"""
        return newFixedLengthResponse(status, "application/json", body)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun b64url(b: ByteArray) = B64.urlNoPad(b)

    companion object {
        const val DEFAULT_PORT = 8765

        /** Starts on the default port or the next few if taken. Returns null if none could bind. */
        fun start(context: Context): PairingServer? {
            for (p in DEFAULT_PORT until DEFAULT_PORT + 10) {
                val s = PairingServer(context, p)
                try {
                    s.start(SOCKET_READ_TIMEOUT, false)
                    return s
                } catch (e: Exception) {
                    runCatching { s.stop() }
                }
            }
            return null
        }

        /** The box's address on the home network: a site-local IPv4 on wifi or ethernet. */
        fun localIp(): String? {
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
            val ranked = ifaces.filter { it.isUp && !it.isLoopback }.sortedBy { i ->
                when {
                    i.name.startsWith("wlan") -> 0
                    i.name.startsWith("eth") -> 1
                    else -> 2
                }
            }
            for (i in ranked) for (a in i.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            return null
        }
    }
}
