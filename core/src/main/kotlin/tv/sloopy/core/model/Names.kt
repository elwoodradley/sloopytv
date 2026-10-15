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
}
