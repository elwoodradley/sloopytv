package tv.tube.core.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import tv.tube.core.model.Programme
import tv.tube.core.net.Limits
import tv.tube.core.net.LimitedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

class XmltvChannel(val id: String, val name: String, val icon: String?)

class XmltvException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming XMLTV parser hardened for untrusted provider feeds.
 *
 *  - Pull parser with DOCTYPE processing off: no external entities, no entity expansion,
 *    so XXE and "billion laughs" style inputs are inert.
 *  - Byte cap applied after gzip decompression (gzip bombs).
 *  - Depth cap, text length caps, element count caps.
 *  - Never builds a DOM; programmes are handed to a sink as they are parsed.
 */
class XmltvParser(
    /** Supplies a fresh pull parser: `android.util.Xml.newPullParser()` on Android, `KXmlParser()` on the JVM. */
    private val parserFactory: () -> XmlPullParser,
    private val maxBytes: Long = Limits.XMLTV_BYTES,
    private val maxProgrammes: Int = Limits.MAX_PROGRAMMES,
    private val defaultZone: ZoneId = ZoneOffset.UTC,
) {
    interface Sink {
        fun channel(c: XmltvChannel)
        fun programme(p: Programme)
    }

    class Counts(var channels: Int = 0, var programmes: Int = 0, var skipped: Int = 0)

    fun parse(raw: InputStream, sink: Sink): Counts {
        val stream = LimitedInputStream(maybeGunzip(raw), maxBytes)
        val parser = parserFactory()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: XmlPullParserException) { }
        try {
            parser.setInput(stream, null)
        } catch (e: XmlPullParserException) {
            throw XmltvException("Guide file is not XML", e)
        }

        val counts = Counts()
        var sawRoot = false
        try {
            var event = parser.eventType
            var depth = 0
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth > MAX_DEPTH) throw XmltvException("Guide XML nested too deeply")
                        if (!sawRoot) {
                            sawRoot = true
                            if (parser.name != "tv") throw XmltvException("Not an XMLTV guide (root element is <${parser.name.take(32)}>)")
                        }
                        when (parser.name) {
                            "channel" -> { readChannel(parser)?.let { sink.channel(it); counts.channels++ }; depth-- }
                            "programme" -> {
                                val p = readProgramme(parser)
                                if (p != null) { sink.programme(p); counts.programmes++ } else counts.skipped++
                                if (counts.programmes > maxProgrammes) throw XmltvException("Guide has more programmes than the app allows")
                                depth--
                            }
                            "tv" -> {}
                            else -> { skip(parser); depth-- }
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                }
                event = parser.nextToken()
            }
        } catch (e: XmlPullParserException) {
            // Truncated or malformed feed. Keep what we have if it is meaningful.
            if (counts.programmes == 0) throw XmltvException("Guide XML is malformed: ${e.message?.take(200)}", e)
        }
        if (!sawRoot) throw XmltvException("Guide file is empty")
        return counts
    }

    /** Reads <channel> ... </channel>; parser is positioned on its START_TAG and leaves on its END_TAG. */
    private fun readChannel(p: XmlPullParser): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")?.trim()?.take(200)
        var name: String? = null
        var icon: String? = null
        while (true) {
            val ev = p.nextToken()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.name == "channel") break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "display-name" -> { val t = text(p); if (name == null && t.isNotBlank()) name = t }
                    "icon" -> { icon = icon ?: p.getAttributeValue(null, "src")?.let(::safeUrl); skip(p) }
                    else -> skip(p)
                }
            }
        }
        if (id.isNullOrEmpty()) return null
        return XmltvChannel(id, name ?: id, icon)
    }

    private fun readProgramme(p: XmlPullParser): Programme? {
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        val channel = p.getAttributeValue(null, "channel")?.trim()?.take(200)
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var episode: String? = null
        var icon: String? = null
        while (true) {
            val ev = p.nextToken()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.name == "programme") break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> { val t = text(p); if (title == null && t.isNotBlank()) title = t }
                    "desc" -> { val t = text(p); if (desc == null && t.isNotBlank()) desc = t }
                    "category" -> { val t = text(p); if (category == null && t.isNotBlank()) category = t }
                    "episode-num" -> {
                        val system = p.getAttributeValue(null, "system")
                        val t = text(p)
                        if (t.isNotBlank() && (episode == null || system == "onscreen")) episode = formatEpisode(system, t)
                    }
                    "icon" -> { icon = icon ?: p.getAttributeValue(null, "src")?.let(::safeUrl); skip(p) }
                    else -> skip(p)
                }
            }
        }
        if (channel.isNullOrEmpty() || start == null) return null
        val end = stop ?: (start + 30 * 60)
        if (end <= start || end - start > 24 * 3600 * 2) return null
        return Programme(
            epgId = channel,
            startEpoch = start,
            stopEpoch = end,
            title = title ?: "Untitled",
            description = desc,
            category = category,
            episode = episode,
            icon = icon,
        )
    }

    /** Collects text (including nested inline tags) of the current element, capped, and consumes its END_TAG. */
    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.nextToken()) {
                XmlPullParser.END_DOCUMENT -> return sb.toString()
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    if (sb.length < Limits.MAX_TEXT) {
                        val t = p.text ?: ""
                        sb.append(t, 0, minOf(t.length, Limits.MAX_TEXT - sb.length))
                    }
                }
            }
        }
        return sb.toString().trim().replace(whitespace, " ")
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.nextToken()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    private fun formatEpisode(system: String?, raw: String): String {
        val t = raw.trim().take(64)
        if (system == "xmltv_ns") {
            // "season.episode.part", zero based, each optionally "n/total"
            val parts = t.split('.')
            val s = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val e = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            return when {
                s != null && e != null -> "S%02dE%02d".format(s, e)
                e != null -> "E%02d".format(e)
                else -> t
            }
        }
        return t
    }

    /** "20261013210000 +0100", "20261013210000", "202610132100" and friends, to epoch seconds. */
    fun parseTime(raw: String?): Long? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.length < 8) return null
        val digits = s.takeWhile { it.isDigit() }
        val padded = (digits + "00000000000000").take(14)
        val ldt = try { LocalDateTime.parse(padded, STAMP) } catch (_: Exception) { return null }
        val rest = s.substring(digits.length).trim()
        val offset: ZoneOffset? = if (rest.isEmpty()) null else try {
            when {
                rest.matches(Regex("[+-]\\d{4}")) -> ZoneOffset.of(rest.substring(0, 3) + ":" + rest.substring(3))
                rest.matches(Regex("[+-]\\d{2}:\\d{2}")) -> ZoneOffset.of(rest)
                rest.equals("UTC", true) || rest == "Z" || rest.equals("GMT", true) -> ZoneOffset.UTC
                else -> null
            }
        } catch (_: Exception) { null }
        return if (offset != null) ldt.toEpochSecond(offset) else ldt.atZone(defaultZone).toEpochSecond()
    }

    private fun safeUrl(u: String): String? {
        val t = u.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t.take(2048) else null
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 2)
        val b1 = pb.read()
        val b2 = pb.read()
        if (b2 >= 0) pb.unread(b2)
        if (b1 >= 0) pb.unread(b1)
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(pb, 64 * 1024) else pb
    }

    companion object {
        private const val MAX_DEPTH = 16
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val whitespace = Regex("\\s+")
    }
}
