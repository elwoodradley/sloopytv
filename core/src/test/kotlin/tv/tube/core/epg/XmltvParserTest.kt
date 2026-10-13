package tv.tube.core.epg

import org.kxml2.io.KXmlParser
import tv.tube.core.model.Programme
import tv.tube.core.net.Limits
import tv.tube.core.net.SizeLimitExceeded
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test-local factory so the existing call sites keep their defaults. */
private fun XmltvParser(maxBytes: Long = Limits.XMLTV_BYTES, maxProgrammes: Int = Limits.MAX_PROGRAMMES) =
    tv.tube.core.epg.XmltvParser({ KXmlParser() }, maxBytes, maxProgrammes)

class XmltvParserTest {
    private class Collect : XmltvParser.Sink {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<Programme>()
        override fun channel(c: XmltvChannel) { channels.add(c) }
        override fun programme(p: Programme) { programmes.add(p) }
    }

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="test">
          <channel id="bbc1.uk"><display-name>BBC One</display-name><icon src="https://x/l.png"/></channel>
          <programme start="20261013210000 +0100" stop="20261013220000 +0100" channel="bbc1.uk">
            <title lang="en">Evening &amp; News</title>
            <desc>Top <b>stories</b> of the day.</desc>
            <category>News</category>
            <episode-num system="xmltv_ns">2.5.</episode-num>
          </programme>
          <programme start="20261013220000 +0100" channel="bbc1.uk"><title>No stop</title></programme>
          <programme start="20261013230000 +0100" stop="20261013224500 +0100" channel="bbc1.uk"><title>Backwards</title></programme>
        </tv>""".trimIndent()

    @Test
    fun `parses channels and programmes`() {
        val c = Collect()
        val counts = XmltvParser().parse(sample.byteInputStream(), c)
        assertEquals(1, counts.channels)
        assertEquals(2, counts.programmes)
        assertEquals(1, counts.skipped)
        assertEquals("BBC One", c.channels[0].name)
        assertEquals("https://x/l.png", c.channels[0].icon)
        val p = c.programmes[0]
        assertEquals("Evening & News", p.title)
        assertEquals("Top stories of the day.", p.description)
        assertEquals("S03E06", p.episode)
        assertEquals(1791921600L, p.startEpoch)   // 2026-10-13 20:00 UTC
        assertEquals(1791925200L, p.stopEpoch)
        // missing stop defaults to 30 minutes
        assertEquals(c.programmes[1].startEpoch + 1800, c.programmes[1].stopEpoch)
    }

    @Test
    fun `external entities are not resolved`() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE tv [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <tv><channel id="c"><display-name>&xxe;</display-name></channel>
            <programme start="20261013210000" stop="20261013220000" channel="c"><title>&xxe;</title></programme></tv>""".trimIndent()
        val c = Collect()
        XmltvParser().parse(xxe.byteInputStream(), c)
        c.channels.forEach { assertFalse(it.name.contains("root:")) }
        c.programmes.forEach { assertFalse(it.title.contains("root:")) }
    }

    @Test
    fun `entity expansion bomb is inert`() {
        val bomb = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE tv [<!ENTITY a \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\">")
            for (i in 1..9) append("<!ENTITY ${"b".repeat(i)} \"${("&${"b".repeat(i - 1).ifEmpty { "a" }};").repeat(10)}\">")
            append("]><tv><programme start=\"20261013210000\" stop=\"20261013220000\" channel=\"c\"><title>&bbbbbbbbb;</title></programme></tv>")
        }
        val c = Collect()
        XmltvParser().parse(bomb.byteInputStream(), c)
        assertTrue(c.programmes.all { it.title.length <= 4_000 })
    }

    @Test
    fun `gzip input is accepted and byte cap applies after decompression`() {
        val big = buildString {
            append("<tv>")
            repeat(2000) { append("<programme start=\"20261013210000\" stop=\"20261013220000\" channel=\"c\"><title>").append("x".repeat(500)).append("</title></programme>") }
            append("</tv>")
        }
        val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(big.toByteArray()) } }.toByteArray()
        assertTrue(gz.size < 100_000)
        val ok = Collect()
        XmltvParser(maxBytes = 10L * 1024 * 1024).parse(gz.inputStream(), ok)
        assertEquals(2000, ok.programmes.size)
        assertFailsWith<SizeLimitExceeded> { XmltvParser(maxBytes = 200_000).parse(gz.inputStream(), Collect()) }
    }

    @Test
    fun `programme cap is enforced`() {
        val many = buildString {
            append("<tv>")
            repeat(50) { append("<programme start=\"20261013210000\" stop=\"20261013220000\" channel=\"c\"><title>t</title></programme>") }
            append("</tv>")
        }
        assertFailsWith<XmltvException> { XmltvParser(maxProgrammes = 10).parse(many.byteInputStream(), Collect()) }
    }

    @Test
    fun `time formats`() {
        val p = XmltvParser()
        assertEquals(1791925200L, p.parseTime("20261013210000 +0000"))
        assertEquals(1791925200L, p.parseTime("20261013210000"))
        assertEquals(1791925200L, p.parseTime("202610132100"))
        assertEquals(1791921600L, p.parseTime("20261013210000 +0100"))
        assertNull(p.parseTime("garbage"))
        assertNull(p.parseTime(null))
    }

    @Test
    fun `non xml fails cleanly`() {
        assertFailsWith<XmltvException> { XmltvParser().parse("<html><body>login</body></html>".byteInputStream(), Collect()) }
    }
}
