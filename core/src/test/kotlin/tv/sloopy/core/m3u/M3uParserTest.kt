package tv.sloopy.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    @Test
    fun `parses attributes and names`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="https://x/l.png" group-title="UK",BBC One HD
            http://h/live/u/p/1.ts
            #EXTINF:-1 tvg-id="" group-title="Sports" catchup-days="7",Sky Sports, Main Event
            #EXTVLCOPT:http-user-agent=x
            https://h/live/u/p/2.m3u8
            #EXTINF:-1,Bare
            file:///etc/passwd
            #EXTINF:-1,Bare2
            http://h/3.ts
        """.trimIndent()
        val list = M3uParser.parse(text.byteInputStream())
        assertEquals(3, list.size)
        assertEquals("BBC One HD", list[0].name)
        assertEquals("bbc1.uk", list[0].tvgId)
        assertEquals("UK", list[0].group)
        assertEquals("Sky Sports, Main Event", list[1].name)
        assertNull(list[1].tvgId)
        assertEquals(7, list[1].catchupDays)
        assertEquals("Bare2", list[2].name)
        val ch = M3uParser.toChannels(list)
        assertTrue(ch[1].archive)
        assertEquals(ch[0].id, M3uParser.toChannels(list)[0].id)
    }

    @Test
    fun `rejects non playlist`() {
        assertFailsWith<M3uException> { M3uParser.parse("<html>".byteInputStream()) }
    }
}
