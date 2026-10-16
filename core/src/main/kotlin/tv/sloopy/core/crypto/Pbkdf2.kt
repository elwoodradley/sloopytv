package tv.sloopy.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2 with HMAC-SHA256 (RFC 8018), written out by hand because Android only ships the
 * "PBKDF2WithHmacSHA256" SecretKeyFactory from API 26. Fire OS 6 (Android 7.1) lacks it, and
 * login codes and transfer codes both derive their AES key this way. HmacSHA256 itself exists
 * on every Android version. Matches the Worker's `crypto.subtle.deriveBits` byte for byte.
 */
object Pbkdf2 {
    fun sha256(password: ByteArray, salt: ByteArray, rounds: Int, lenBytes: Int): ByteArray {
        require(rounds >= 1 && lenBytes >= 1)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hLen = mac.macLength
        val blocks = (lenBytes + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (r in 2..rounds) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(lenBytes)
    }

    /** The app's one shape: a code as UTF-8, 100,000 rounds, a 256-bit AES key. */
    fun aesKey(code: String, salt: ByteArray): ByteArray = sha256(code.toByteArray(Charsets.UTF_8), salt, 100_000, 32)
}
