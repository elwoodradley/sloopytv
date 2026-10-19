package tv.sloopy

import android.content.Context
import java.io.File

/**
 * A short record of what changed this stick's settings, kept in a plain file outside the
 * settings store (the store is what it watches). The buddy's clock, badge and frame-rate
 * settings "kept resetting" and his friend code changed overnight (2026-10-18), and nothing in
 * the app could say why. Settings › About shows the last lines and when the app was installed.
 */
object SettingsLog {
    private const val MAX = 40
    private fun file(context: Context) = File(context.applicationContext.filesDir, "settings_log.txt")

    fun note(context: Context, what: String) {
        runCatching {
            val f = file(context)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp · v${BuildConfig.VERSION_NAME} · $what"
            f.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
        }
    }

    fun read(context: Context, last: Int = 10): List<String> = runCatching { file(context).readLines().filter { it.isNotBlank() }.takeLast(last) }.getOrDefault(emptyList())

    /** "Installed 2026-10-12 · updated 2026-10-18": a recent install date means the app was removed and put back, which wipes every setting. */
    fun installLine(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        "Installed ${f.format(java.util.Date(pi.firstInstallTime))} · last updated ${f.format(java.util.Date(pi.lastUpdateTime))}"
    }.getOrDefault("")
}
