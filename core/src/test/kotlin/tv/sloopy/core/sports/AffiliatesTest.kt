package tv.sloopy.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffiliatesTest {
    @Test fun city_and_state_beats_everything() {
        assertEquals(4, Affiliates.rank("FOX", "FOX 28 COLUMBUS OH"))
        assertEquals(4, Affiliates.rank("ABC", "ABC 6 COLUMBUS OHIO"))
        assertEquals(4, Affiliates.rank("CBS", "WBNS CBS COLUMBUS OH"))
        assertEquals(3, Affiliates.rank("NBC", "WCMH NBC"))
        assertEquals(3, Affiliates.rank("FOX", "FOX CLEVELAND"))
        assertEquals(2, Affiliates.rank("FOX", "FOX"))
        assertEquals(2, Affiliates.rank("ABC", "ABC EAST HD"))
        assertEquals(1, Affiliates.rank("ABC", "ABC OHIO"))
        assertEquals(1, Affiliates.rank("ABC", "ABC NY"))
    }

    @Test fun country_prefix_is_not_a_city() {
        assertEquals(4, Affiliates.rank("FOX", "US FOX 28 COLUMBUS OH"))
        assertEquals(2, Affiliates.rank("FOX", "UK FOX"))
        assertEquals(2, Affiliates.rank("FOX", "CA FOX HD"))
        assertEquals(1, Affiliates.rank("ABC", "US ABC OHIO"))
    }

    @Test fun look_alikes_are_out() {
        assertNull(Affiliates.rank("FOX", "FOX SOUL"))
        assertNull(Affiliates.rank("FOX", "US FOX PRIME HD"))
        assertNull(Affiliates.rank("ABC", "ABC PRIME"))
        assertNull(Affiliates.rank("CBS", "CBS PRIME EAST"))
        assertEquals(2, Affiliates.rank("PRIME VIDEO", "PRIME VIDEO", listOf("PRIME VIDEO", "AMAZON")))
        assertNull(Affiliates.rank("FOX", "FOX NEWS HD"))
        assertNull(Affiliates.rank("FOX", "FOX SPORTS 1"))
        assertNull(Affiliates.rank("ABC", "ABC NEWS LIVE"))
        assertNull(Affiliates.rank("CBS", "CBS SPORTS NETWORK"))
        assertNull(Affiliates.rank("NBC", "CNBC"))
        assertNull(Affiliates.rank("ESPN", "ESPN DEPORTES"))
    }

    @Test fun multi_word_networks_still_match() {
        assertEquals(2, Affiliates.rank("NFL NETWORK", "NFL NETWORK HD", listOf("NFL NETWORK", "NFL NET")))
        assertEquals(2, Affiliates.rank("FS1", "FOX SPORTS 1", listOf("FS1", "FOX SPORTS 1")))
        assertTrue(Affiliates.rank("FS1", "FS1 HD", listOf("FS1", "FOX SPORTS 1")) != null)
    }
}
