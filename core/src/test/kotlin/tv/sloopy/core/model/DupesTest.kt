package tv.sloopy.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DupesTest {
    @Test fun `copies share a key`() {
        assertEquals(Dupes.key("ESPN HD"), Dupes.key("ESPN FHD"))
        assertEquals(Dupes.key("US| ESPN 4K"), Dupes.key("US: ESPN"))
        assertNotEquals(Dupes.key("US| ESPN 4K"), Dupes.key("UK| ESPN"))
        assertEquals(Dupes.key("ESPN HD"), Dupes.key("ESPN 4K"))
        assertEquals(Dupes.key("##### ESPN BACKUP #####"), Dupes.key("ESPN"))
        assertEquals(Dupes.key("UK: BBC ONE HD"), Dupes.key("UK| BBC ONE"))
    }
    @Test fun `lookalikes never share a key`() {
        assertNotEquals(Dupes.key("ESPN"), Dupes.key("ESPN2"))
        assertNotEquals(Dupes.key("ESPN"), Dupes.key("ESPNU"))
        assertNotEquals(Dupes.key("ESPN"), Dupes.key("ESPN 2"))
        assertNotEquals(Dupes.key("CBS EAST"), Dupes.key("CBS WEST"))
        assertNotEquals(Dupes.key("NBC (NY)"), Dupes.key("NBC (LA)"))
        assertNotEquals(Dupes.key("ESPN"), Dupes.key("ESPN PLUS"))
    }
    @Test fun `quality ranks tags`() {
        assertEquals(4, Dupes.quality("ESPN 4K"))
        assertEquals(3, Dupes.quality("US| ESPN FHD"))
        assertEquals(2, Dupes.quality("ESPN HD"))
        assertEquals(0, Dupes.quality("ESPN"))
    }
    @Test fun `tags describe the copy`() {
        assertEquals("FHD · BACKUP", Dupes.tags("US| ESPN FHD BACKUP"))
        assertEquals("", Dupes.tags("ESPN"))
    }
    @Test fun `all decoration leaves an empty key`() = assertEquals("", Dupes.key("HD"))
}
