package tv.sloopy.core.util

import kotlin.io.encoding.Base64

/** Base64 without java.util.Base64, which only exists from Android 8 (API 26). Fire OS 6 is API 25. */
object B64 {
    private val lax = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    fun encode(b: ByteArray): String = Base64.encode(b)
    fun decode(s: String): ByteArray = lax.decode(s)
    fun urlNoPad(b: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(b)
}
