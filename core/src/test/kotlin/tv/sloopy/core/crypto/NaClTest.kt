package tv.sloopy.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Vectors produced by tweetnacl-js 1.0.3 (`nacl.secretbox`) under JavaScriptCore. */
class NaClTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val nonce = hex("202122232425262728292a2b2c2d2e2f3031323334353637")

    @Test
    fun `empty message`() {
        val box = hex("aef08ad21579467890970753aeaee026")
        assertEquals("", SecretBox.open(box, nonce, key)!!.hex())
        assertEquals(box.hex(), SecretBox.seal(ByteArray(0), nonce, key).hex())
    }

    @Test
    fun `short message`() {
        val box = hex("a0dd0130169d5d15fb84e7014ee51685b93c21c922")
        assertEquals("Hello", SecretBox.open(box, nonce, key)!!.decodeToString())
        assertEquals(box.hex(), SecretBox.seal("Hello".toByteArray(), nonce, key).hex())
    }

    @Test
    fun `json payload`() {
        val msg = """{"url":"http://host:8080/","user":"alice","pass":"s3cr3t"}"""
        val box = hex("80ec64a7963a778326db60cb1ab62f598a7b38d7210560b46141211e3667989cd6c1c9b1c9291b4493541fb0b768c98f9531f2a297c01aaa814271fb94ae0d7089f4d9fa6b44c78b8b26")
        assertEquals(msg, SecretBox.open(box, nonce, key)!!.decodeToString())
        assertEquals(box.hex(), SecretBox.seal(msg.toByteArray(), nonce, key).hex())
    }

    @Test
    fun `tampering is rejected`() {
        val box = hex("a0dd0130169d5d15fb84e7014ee51685b93c21c922")
        box[20] = (box[20].toInt() xor 1).toByte()
        assertNull(SecretBox.open(box, nonce, key))
        val wrongKey = key.copyOf().also { it[0] = 9 }
        assertNull(SecretBox.open(hex("a0dd0130169d5d15fb84e7014ee51685b93c21c922"), nonce, wrongKey))
        assertNull(SecretBox.open(ByteArray(5), nonce, key))
    }

    @Test
    fun `round trip of a longer message crosses block boundaries`() {
        val msg = ByteArray(300) { (it * 7).toByte() }
        val sealed = SecretBox.seal(msg, nonce, key)
        assertContentEquals(msg, SecretBox.open(sealed, nonce, key))
    }

    @Test
    fun `pair key derivation is deterministic and 32 bytes`() {
        val salt = hex("00112233445566778899aabbccddeeff")
        val a = PairKey.derive("645556", salt)
        val b = PairKey.derive("645556", salt)
        assertEquals(32, a.size)
        assertContentEquals(a, b)
        assert(!a.contentEquals(PairKey.derive("645557", salt)))
    }
}
