package tv.sloopy.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Vectors from RFC 7914 §11 (PBKDF2-HMAC-SHA256) and the JDK's own PBKDF2WithHmacSHA256. */
class Pbkdf2Test {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun rfc7914_one_round() {
        val k = Pbkdf2.sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            k.hex(),
        )
    }

    @Test fun rfc7914_many_rounds() {
        val k = Pbkdf2.sha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            k.hex(),
        )
    }

    @Test fun matches_the_jdk_factory() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        val jdk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec("483920".toCharArray(), salt, 100_000, 256)).encoded
        assertEquals(jdk.hex(), Pbkdf2.aesKey("483920", salt).hex())
    }
}
