package tv.sloopy.core.model

/** Provider names come dressed up ("##### SOCCER PPV #####", "|US| ESPN |"). Strip the dressing, keep the name. */
object Names {
    private val edge = Regex("^[\\s#*=~_\\-|:.•·◆►▶◄◀\\[\\]{}()<>]+|[\\s#*=~_\\-|:.•·◆►▶◄◀\\[\\]{}()<>]+$")
    private val runs = Regex("[#*=~]{2,}")
    private val spaces = Regex("\\s{2,}")

    fun clean(raw: String): String {
        var s = raw.replace(runs, " ")
        s = s.replace(edge, "")
        s = s.replace(spaces, " ").trim()
        return if (s.isEmpty()) raw.trim() else s
    }

    /**
     * Whether [candidate] is the programme or title [wanted]: the same title, or [wanted] followed
     * by a separator (season tags, "(2024)", "- Live"). "The Bear" matches "The Bear S03E01" and
     * "The Bear (2022)" but not "The Bearcats" or "Bear".
     */
    fun sameTitle(candidate: String, wanted: String): Boolean {
        val c = candidate.lowercase().trim(); val t = wanted.lowercase().trim()
        if (t.isEmpty()) return false
        if (c == t) return true
        if (!c.startsWith(t)) return false
        val next = c.getOrNull(t.length) ?: return true
        return !next.isLetterOrDigit()
    }
}
