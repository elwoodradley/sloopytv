package tv.sloopy.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Blob produced by the Worker in codes/src/index.js for code 887934. */
class CodeClientTest {
    private fun b(s: String) = Base64.getDecoder().decode(s)
    private val salt = b("pNe7r0Z7gk1KSifLAcDZ4w==")
    private val iv = b("oIIyzc/UaOKbW9lo")
    private val ct = b("Ga1kXUj9/Quw5AivDKopLGVgEtgcnoYNQsxDYKKZ8Ov3jeSJDKfmp05R/JARA+CPWNIuM0Y304u1v3PcvmYOcOFeUJRlfd1O/3uqvt7bIU9eP/aMBKT/Faoc1M9L5A==")

    @Test
    fun `right code decrypts the worker's blob`() {
        val plain = CodeClient.decrypt("887934", salt, iv, ct)!!.decodeToString()
        assertEquals("""{"url":"http://127.0.0.1:1","user":"alice","pass":"s3cret","name":"Code test"}""", plain)
    }

    @Test
    fun `wrong code fails closed`() {
        assertNull(CodeClient.decrypt("887935", salt, iv, ct))
        val tampered = ct.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertNull(CodeClient.decrypt("887934", salt, iv, tampered))
    }
}
