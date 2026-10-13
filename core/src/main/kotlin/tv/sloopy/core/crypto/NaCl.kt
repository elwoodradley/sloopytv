package tv.sloopy.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * NaCl `crypto_secretbox` (XSalsa20 + Poly1305), the primitive tweetnacl-js exposes as
 * `nacl.secretbox`. Used for phone-to-TV pairing: the browser on the phone encrypts with
 * tweetnacl, the TV opens the box here. Pure Kotlin, verified against tweetnacl output.
 *
 * Not a general purpose crypto library: messages are a few hundred bytes, so Poly1305 uses
 * BigInteger for clarity rather than speed.
 */
object SecretBox {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16

    /** Returns the plaintext, or null if the tag does not verify. */
    fun open(box: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be 24 bytes" }
        if (box.size < TAG_BYTES) return null
        val mlen = box.size - TAG_BYTES
        val stream = xsalsa20(key, nonce, 32 + mlen)
        val polyKey = stream.copyOfRange(0, 32)
        val body = box.copyOfRange(TAG_BYTES, box.size)
        val tag = Poly1305.mac(body, polyKey)
        if (!constantTimeEquals(tag, box.copyOfRange(0, TAG_BYTES))) return null
        val out = ByteArray(mlen)
        for (i in 0 until mlen) out[i] = (body[i].toInt() xor stream[32 + i].toInt()).toByte()
        return out
    }

    /** tag || ciphertext, exactly what tweetnacl's `nacl.secretbox` returns. */
    fun seal(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be 24 bytes" }
        val stream = xsalsa20(key, nonce, 32 + message.size)
        val body = ByteArray(message.size) { i -> (message[i].toInt() xor stream[32 + i].toInt()).toByte() }
        val tag = Poly1305.mac(body, stream.copyOfRange(0, 32))
        return tag + body
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    // ---- XSalsa20 ---------------------------------------------------------------------------

    private fun xsalsa20(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
        val subkey = Salsa20.hsalsa20(key, nonce.copyOfRange(0, 16))
        return Salsa20.stream(subkey, nonce.copyOfRange(16, 24), length)
    }
}

internal object Salsa20 {
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) // "expand 32-byte k"

    private fun le(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    private fun put(out: ByteArray, i: Int, v: Int) {
        out[i] = v.toByte(); out[i + 1] = (v ushr 8).toByte(); out[i + 2] = (v ushr 16).toByte(); out[i + 3] = (v ushr 24).toByte()
    }

    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    /** 20 rounds over the 16-word state; returns the state without the final feed-forward add. */
    private fun rounds(input: IntArray): IntArray {
        val x = input.copyOf()
        repeat(10) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9); x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9); x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9); x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9); x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)
            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9); x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9); x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9); x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9); x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        return x
    }

    private fun state(key: ByteArray, n16: ByteArray): IntArray = intArrayOf(
        SIGMA[0], le(key, 0), le(key, 4), le(key, 8), le(key, 12),
        SIGMA[1], le(n16, 0), le(n16, 4), le(n16, 8), le(n16, 12),
        SIGMA[2], le(key, 16), le(key, 20), le(key, 24), le(key, 28), SIGMA[3],
    )

    fun hsalsa20(key: ByteArray, n16: ByteArray): ByteArray {
        val x = rounds(state(key, n16))
        val out = ByteArray(32)
        val idx = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        for (i in 0 until 8) put(out, i * 4, x[idx[i]])
        return out
    }

    fun stream(key: ByteArray, nonce8: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        val n16 = ByteArray(16)
        System.arraycopy(nonce8, 0, n16, 0, 8)
        var counter = 0L
        var pos = 0
        while (pos < length) {
            for (i in 0 until 8) n16[8 + i] = (counter ushr (8 * i)).toByte()
            val input = state(key, n16)
            val x = rounds(input)
            val block = ByteArray(64)
            for (i in 0 until 16) put(block, i * 4, x[i] + input[i])
            val n = minOf(64, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}

internal object Poly1305 {
    private val P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))

    fun mac(msg: ByteArray, key: ByteArray): ByteArray {
        val r = key.copyOfRange(0, 16)
        r[3] = (r[3].toInt() and 15).toByte(); r[7] = (r[7].toInt() and 15).toByte(); r[11] = (r[11].toInt() and 15).toByte(); r[15] = (r[15].toInt() and 15).toByte()
        r[4] = (r[4].toInt() and 252).toByte(); r[8] = (r[8].toInt() and 252).toByte(); r[12] = (r[12].toInt() and 252).toByte()
        val rn = leToBig(r)
        val s = leToBig(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val n = minOf(16, msg.size - i)
            val block = ByteArray(n + 1)
            System.arraycopy(msg, i, block, 0, n)
            block[n] = 1
            acc = acc.add(leToBig(block)).multiply(rn).mod(P)
            i += n
        }
        acc = acc.add(s).mod(BigInteger.ONE.shiftLeft(128))
        return bigToLe(acc, 16)
    }

    private fun leToBig(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun bigToLe(v: BigInteger, n: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(n)
        var j = be.size - 1
        var i = 0
        while (i < n && j >= 0) { out[i] = be[j]; i++; j-- }
        return out
    }
}

/**
 * Key for the "type the code" path, where the phone never received the QR's random key.
 * Mirrors the page's JavaScript exactly: h = sha512(code || salt), then 20000 times
 * h = sha512(h || salt); the key is the first 32 bytes.
 */
object PairKey {
    const val ITERATIONS = 20_000

    fun derive(code: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        var h = md.digest(code.toByteArray(Charsets.UTF_8) + salt)
        repeat(ITERATIONS) { md.reset(); h = md.digest(h + salt) }
        return h.copyOfRange(0, 32)
    }
}
