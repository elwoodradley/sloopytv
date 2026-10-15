package tv.sloopy.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test fun `strips hash dressing`() = assertEquals("SOCCER PPV", Names.clean("##### SOCCER PPV #####"))
    @Test fun `keeps country prefix inside`() = assertEquals("US| ESPN", Names.clean("|US| ESPN |"))
    @Test fun `plain names untouched`() = assertEquals("Big Buck Bunny", Names.clean("Big Buck Bunny"))
    @Test fun `all dressing falls back to raw`() = assertEquals("###", Names.clean("###"))
    @Test fun `collapses inner runs`() = assertEquals("4K UHD", Names.clean("4K === UHD"))
}
