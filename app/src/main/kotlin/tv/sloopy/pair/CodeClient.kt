package tv.sloopy.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.sloopy.data.AndroidHttp
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Redeems a six-digit login code against a SloopyTV code server (see codes/ in the repo).
 * The server returns the login encrypted under a key derived from the code; it is decrypted
 * here on the TV and never leaves it.
 */
object CodeClient {
    private val json = Json { ignoreUnknownKeys = true }

    class CodeException(message: String) : IOException(message)

    suspend fun redeem(server: String, code: String): PairPayload = withContext(Dispatchers.IO) {
        val base = server.trim().trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) throw CodeException("No code server is set up")
        val c = code.filter { it.isDigit() }
        if (c.length != 6) throw CodeException("A code is six digits")
        val req = Request.Builder().url("$base/redeem")
            .post("""{"code":"$c"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val body = try {
            AndroidHttp.client.newCall(req).execute().use { r -> r.body?.string() ?: "" }
        } catch (e: IOException) {
            throw CodeException("Couldn't reach the code server")
        }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw CodeException("The code server sent something unexpected")
        if ((root["ok"] as? JsonPrimitive)?.contentOrNull != "true") throw CodeException(root.str("error") ?: "That code didn't work")

        val salt = b64(root.str("salt")); val iv = b64(root.str("iv")); val ct = b64(root.str("ct"))
        if (salt == null || iv == null || ct == null) throw CodeException("The code server sent something unexpected")
        val plain = decrypt(c, salt, iv, ct) ?: throw CodeException("That code didn't unlock the login")
        val p = runCatching { json.parseToJsonElement(plain.decodeToString()) as? JsonObject }.getOrNull()
            ?: throw CodeException("The login behind that code is damaged")
        PairPayload(
            name = p.str("name").orEmpty().take(60),
            url = p.str("url").orEmpty().take(2048),
            user = p.str("user").orEmpty().take(256),
            pass = p.str("pass").orEmpty().take(256),
        )
    }

    /** PBKDF2-SHA256 (100k rounds) from the code and salt, then AES-256-GCM. Mirrors the Worker exactly. */
    fun decrypt(code: String, salt: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray? = try {
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, 100_000, 256)).encoded
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            doFinal(ct)
        }
    } catch (e: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}
