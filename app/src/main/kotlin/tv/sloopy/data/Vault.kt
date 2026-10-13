package tv.sloopy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secret storage backed by the Android Keystore.
 *
 * A single AES-256-GCM key lives in hardware-backed storage (TEE or StrongBox where the box
 * has one) and never leaves it. Values are encrypted with a fresh random IV and written to
 * app-private files that are excluded from backup. The key is not usable by any other app
 * and is destroyed on uninstall.
 */
class Vault(context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            gen.generateKey()
        }
    }

    @Synchronized
    fun put(name: String, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { it.write(iv.size); it.write(iv); it.write(ct) }
        if (!tmp.renameTo(File(dir, "$name.bin"))) {
            File(dir, "$name.bin").delete()
            tmp.renameTo(File(dir, "$name.bin"))
        }
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val f = File(dir, "$name.bin")
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            // Key rotated by the OS (factory reset restore, lock screen change on old devices): the
            // blob is unreadable and worthless. Drop it rather than crash on every launch.
            f.delete()
            null
        }
    }

    @Synchronized
    fun remove(name: String) { File(dir, "$name.bin").delete() }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))
    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sloopy.vault.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** PBKDF2-free but salted and iterated hash for the parental PIN. A 4 to 6 digit PIN is never worth more than this. */
        fun hashPin(pin: String, salt: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            var h = md.digest(salt + pin.toByteArray())
            repeat(20_000) { md.reset(); h = md.digest(h + salt) }
            return h
        }

        fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}
