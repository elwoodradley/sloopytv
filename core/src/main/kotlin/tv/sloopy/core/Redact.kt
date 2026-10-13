package tv.sloopy.core

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Scrubs provider credentials from anything that might reach a log line, a crash report,
 * an error message shown on screen, or a share sheet.
 *
 * Two layers:
 *  1. Pattern based: Xtream puts the username and password in every URL, both as
 *     `?username=..&password=..` and as path segments `/live/USER/PASS/123.ts`.
 *     These are rewritten even when the secrets are not yet registered.
 *  2. Value based: every secret registered with [register] is replaced wherever it appears,
 *     including inside JSON bodies or exception messages.
 *
 * The replacement is deliberately a visible marker so that a leak in a test fixture is
 * obvious rather than silent.
 */
object Redact {
    const val MASK = "•••"

    private val secrets = CopyOnWriteArraySet<String>()

    private val queryParam = Regex("""(?i)([?&](?:username|password|user|pass|pwd|token|key)=)[^&#\s"']*""")
    private val pathCreds = Regex("""(?i)(/(?:live|movie|series|timeshift|streaming)/)[^/\s"']+/[^/\s"']+(/)""")
    private val basicAuth = Regex("""(?i)(https?://)[^/@\s]+:[^/@\s]+@""")

    fun register(vararg values: String?) {
        values.filterNotNull().filter { it.length >= 3 }.forEach { secrets.add(it) }
    }

    fun forget(vararg values: String?) {
        values.filterNotNull().forEach { secrets.remove(it) }
    }

    fun clear() = secrets.clear()

    /** Returns [text] with every known or recognisable credential replaced by [MASK]. */
    fun apply(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        out = queryParam.replace(out) { m -> m.groupValues[1] + MASK }
        out = pathCreds.replace(out) { m -> m.groupValues[1] + MASK + "/" + MASK + m.groupValues[2] }
        out = basicAuth.replace(out) { m -> m.groupValues[1] + MASK + ":" + MASK + "@" }
        // Longest secrets first so a password that contains the username is not half masked.
        for (s in secrets.sortedByDescending { it.length }) {
            if (out.contains(s)) out = out.replace(s, MASK)
        }
        return out
    }

    /** Convenience for exceptions: message plus cause chain, all scrubbed. */
    fun apply(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) sb.append(" <- ")
            sb.append(cur::class.simpleName).append(": ").append(apply(cur.message))
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }
}
