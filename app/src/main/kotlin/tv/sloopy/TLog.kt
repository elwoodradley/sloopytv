package tv.sloopy

import android.util.Log
import tv.sloopy.core.Redact

/**
 * The only logger in the app. Every message is passed through [Redact] and nothing is
 * written at all in release builds. Never call android.util.Log directly.
 */
object TLog {
    private const val TAG = "Sloopy"
    private val enabled = BuildConfig.DEBUG

    fun d(msg: () -> String) { if (enabled) Log.d(TAG, Redact.apply(msg())) }
    fun i(msg: () -> String) { if (enabled) Log.i(TAG, Redact.apply(msg())) }
    fun w(msg: () -> String) { if (enabled) Log.w(TAG, Redact.apply(msg())) }
    fun e(t: Throwable? = null, msg: () -> String) {
        if (enabled) Log.e(TAG, Redact.apply(msg()) + (t?.let { " | " + Redact.apply(it) } ?: ""))
    }

    /** A user-facing message for an exception, scrubbed. */
    fun userMessage(t: Throwable): String = Redact.apply(t.message ?: t::class.simpleName ?: "Something went wrong")
}
