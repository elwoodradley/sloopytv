package tv.sloopy

import android.content.Context
import java.io.File
import tv.sloopy.core.Redact

/**
 * The release build is silent, so a crash on a friend's stick leaves nothing to read. This keeps
 * the last one (scrubbed of URLs and credentials) in the app's own files and About shows it.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val text = buildString {
                    append("SloopyTV ${BuildConfig.VERSION_NAME} · ${java.time.ZonedDateTime.now()} · thread ${thread.name}\n")
                    append(Redact.apply(t))
                    var cause = t.cause; var depth = 0
                    while (cause != null && depth++ < 4) { append("\nCaused by: ").append(Redact.apply(cause)); cause = cause.cause }
                }
                file(app).writeText(text.take(6000))
            }
            previous?.uncaughtException(thread, t)
        }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(context: Context) { runCatching { file(context).delete() } }
}
