package tv.sloopy.core.model

/**
 * Tells copies of a channel apart from channels that merely look alike. Two channels are the
 * same channel only when their names are identical once decoration is stripped: quality tags,
 * backup markers, bracketed numbers. A country prefix is part of the identity, not decoration:
 * "US| ESPN HD" and "US: ESPN 4K" share a key, "UK| ESPN" does not (a viewer found UK feeds
 * standing in for US ones). "ESPN", "ESPN2" and "ESPNU" never share. Anything weaker is left
 * to the viewer to confirm.
 */
object Dupes {
    private val QUALITY = mapOf(
        "8K" to 5, "4K" to 4, "UHD" to 4, "2160P" to 4, "FHD" to 3, "1080P" to 3, "1080" to 3, "1080I" to 3,
        "HD" to 2, "720P" to 2, "720" to 2, "HEVC" to 2, "H265" to 2, "H.265" to 2, "SD" to 1, "LOW" to 1,
    )
    private val STRIP = setOf("HD", "FHD", "UHD", "SD", "4K", "8K", "2160P", "1080P", "1080", "1080I", "720P", "720", "HEVC", "H265", "H.265",
        "50FPS", "60FPS", "25FPS", "30FPS", "LOW", "BACKUP", "BK", "B1", "B2", "B3", "ALT", "RAW", "VIP", "MAIN", "FEED", "LIVE")
    private val prefix = Regex("^\\(?\\[?[A-Z]{2,3}\\]?\\)?\\s*[|:/\\-]\\s*")
    private val bracketNumber = Regex("[\\[(]\\s*\\d{1,2}\\s*[\\])]")
    private val sep = Regex("[\\s_|:/\\-]+")

    /** The cluster key: what is left of the name after decoration. Empty when nothing is left. */
    fun key(raw: String): String {
        var s = Names.clean(raw).uppercase()
        val country = prefix.find(s)?.value?.filter { it.isLetter() } ?: ""
        s = s.replace(prefix, "")
        s = s.replace(bracketNumber, " ")
        val tokens = s.split(sep).filter { it.isNotBlank() }
        val kept = tokens.filter { it !in STRIP }
        if (kept.isEmpty()) return ""
        return (if (country.isEmpty()) "" else "$country ") + kept.joinToString(" ")
    }

    /** 0 unknown … 5 best. Read off the tags the key strips. */
    fun quality(raw: String): Int {
        val tokens = Names.clean(raw).uppercase().split(sep).filter { it.isNotBlank() }
        return tokens.maxOfOrNull { QUALITY[it] ?: 0 } ?: 0
    }

    /** The tags that made this copy different, for the picker: "FHD · BACKUP". */
    fun tags(raw: String): String {
        val tokens = Names.clean(raw).uppercase().replace(prefix, "").split(sep).filter { it.isNotBlank() }
        return tokens.filter { it in STRIP }.joinToString(" · ")
    }
}
