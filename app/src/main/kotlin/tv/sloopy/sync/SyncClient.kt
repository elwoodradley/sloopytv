package tv.sloopy.sync

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import tv.sloopy.core.util.B64
import tv.sloopy.core.crypto.Pbkdf2
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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

/**
 * Carries the encrypted settings bundle to and from the code server. The code is the key: the
 * server sees only an id derived from it and a blob it cannot read.
 */
object SyncClient {
    class SyncException(message: String) : IOException(message)

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // no 0/O/1/I
    private val json = Json { ignoreUnknownKeys = true }

    /** Eight characters shown as XXXX-XXXX. */
    fun newCode(): String {
        val r = SecureRandom()
        return (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }
    fun normalise(code: String): String = code.uppercase().filter { it in ALPHABET }
    fun pretty(code: String): String = normalise(code).chunked(4).joinToString("-")

    private fun id(code: String): String = MessageDigest.getInstance("SHA-256").digest(("sloopytv-sync|" + normalise(code)).toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    private fun key(code: String, salt: ByteArray): SecretKeySpec = SecretKeySpec(
        Pbkdf2.aesKey(normalise(code), salt), "AES",
    )

    suspend fun upload(server: String, code: String, plain: ByteArray): Long = withContext(Dispatchers.IO) {
        val c = normalise(code)
        if (c.length != 8) throw SyncException("A transfer code is eight letters and numbers")
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key(c, salt), GCMParameterSpec(128, iv)); doFinal(plain) }
        val body = """{"id":"${id(c)}","salt":"${B64.encode(salt)}","iv":"${B64.encode(iv)}","ct":"${B64.encode(ct)}"}"""
        val root = call(Request.Builder().url(base(server) + "/sync/put").post(body.toRequestBody("application/json".toMediaType())).build())
        (root["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis()
    }

    suspend fun download(server: String, code: String): ByteArray = withContext(Dispatchers.IO) {
        val c = normalise(code)
        if (c.length != 8) throw SyncException("A transfer code is eight letters and numbers")
        val root = call(Request.Builder().url(base(server) + "/sync/get?id=" + id(c)).get().build())
        val salt = root.str("salt")?.let { B64.decode(it) }; val iv = root.str("iv")?.let { B64.decode(it) }; val ct = root.str("ct")?.let { B64.decode(it) }
        if (salt == null || iv == null || ct == null) throw SyncException("The server sent something unexpected")
        try {
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(c, salt), GCMParameterSpec(128, iv)); doFinal(ct) }
        } catch (e: Exception) { throw SyncException("That code did not unlock the backup") }
    }

    private fun base(server: String): String {
        val b = server.trim().trimEnd('/')
        if (!b.startsWith("https://") && !b.startsWith("http://")) throw SyncException("No code server is set up")
        return b
    }
    private fun call(req: Request): JsonObject {
        val body = try { AndroidHttp.client.newCall(req).execute().use { r -> r.body?.string() ?: "" } } catch (e: IOException) { throw SyncException("Couldn't reach the code server") }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw SyncException("The server sent something unexpected")
        if (root.str("ok") != "true") throw SyncException(root.str("error") ?: "The server refused")
        return root
    }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
