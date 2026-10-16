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

    /**
     * The last time Android itself closed the app (a freeze it gave up on, or memory pressure),
     * which never reaches the exception handler. Android 11+ keeps the record; read once per launch.
     */
    fun noteSystemExit(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val why = when (last.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "the app stopped responding and Android closed it"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed it to free memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed it for using too much"
                android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed"
                else -> return
            }
            val f = java.io.File(context.applicationContext.filesDir, "last_exit.txt")
            val stamp = java.time.Instant.ofEpochMilli(last.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ')
            val line = "$stamp · $why" + (last.description?.let { " · " + Redact.apply(it).take(120) } ?: "")
            if (f.exists() && f.readText().startsWith(stamp)) return
            f.writeText(line)
        }
    }
    fun readSystemExit(context: Context): String? = runCatching { java.io.File(context.applicationContext.filesDir, "last_exit.txt").takeIf { it.exists() }?.readText() }.getOrNull()
    fun clearSystemExit(context: Context) { runCatching { java.io.File(context.applicationContext.filesDir, "last_exit.txt").delete() } }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(context: Context) { runCatching { file(context).delete() } }
}
