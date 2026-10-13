package tv.tube.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactTest {
    private val m = Redact.MASK

    @Test
    fun `query credentials are masked without registration`() {
        Redact.clear()
        val out = Redact.apply("http://h.example:8080/player_api.php?username=alice&password=s3cret&action=get_live_streams")
        assertEquals("http://h.example:8080/player_api.php?username=$m&password=$m&action=get_live_streams", out)
    }

    @Test
    fun `path credentials are masked`() {
        Redact.clear()
        assertEquals("http://h/live/$m/$m/123.ts", Redact.apply("http://h/live/alice/s3cret/123.ts"))
        assertEquals("http://h/movie/$m/$m/9.mkv", Redact.apply("http://h/movie/alice/s3cret/9.mkv"))
        assertEquals("http://h/series/$m/$m/9.mkv", Redact.apply("http://h/series/alice/s3cret/9.mkv"))
    }

    @Test
    fun `registered secrets are masked anywhere`() {
        Redact.clear()
        Redact.register("alice", "hunter2hunter2")
        val out = Redact.apply("""{"user_info":{"username":"alice","password":"hunter2hunter2"}} failed for alice""")
        assertFalse(out.contains("alice"))
        assertFalse(out.contains("hunter2hunter2"))
        assertTrue(out.contains(m))
    }

    @Test
    fun `basic auth in url is masked`() {
        Redact.clear()
        assertEquals("http://$m:$m@h/x", Redact.apply("http://bob:pw@h/x"))
    }

    @Test
    fun `exception chain is scrubbed`() {
        Redact.clear()
        val e = RuntimeException("outer http://h/live/u/p/1.ts", IllegalStateException("inner ?password=zzz"))
        val out = Redact.apply(e)
        assertFalse(out.contains("/u/p/"))
        assertFalse(out.contains("zzz"))
    }
}
