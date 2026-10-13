package tv.tube.core.xtream

import kotlinx.coroutines.runBlocking
import tv.tube.core.Redact
import tv.tube.core.model.StreamContainer
import tv.tube.core.net.Http
import tv.tube.core.net.HttpResponse
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtreamApiTest {
    private class FakeHttp(val routes: Map<String, String>) : Http {
        val seen = ArrayList<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse {
            seen.add(url)
            val key = routes.keys.firstOrNull { url.contains(it) } ?: return HttpResponse(404, "text/html", 0, ByteArray(0).inputStream())
            val body = routes.getValue(key).toByteArray()
            return HttpResponse(200, "application/json", body.size.toLong(), body.inputStream())
        }
    }

    private val creds = XtreamCredentials.parse("h.example:8080", "alice", "s3cret")

    @Test
    fun `credentials parse from pasted get php url`() {
        val c = XtreamCredentials.parse("http://h.example:8080/get.php?username=bob&password=pw1&type=m3u_plus", null, null)
        assertEquals("http://h.example:8080", c.baseUrl)
        assertEquals("bob", c.username)
        assertEquals("pw1", c.password)
        assertTrue(c.isPlainHttp)
        assertFailsWith<XtreamException> { XtreamCredentials.parse("ftp://h", "a", "b") }
        assertFailsWith<XtreamException> { XtreamCredentials.parse("h", "", "b") }
    }

    @Test
    fun `stream urls`() {
        assertEquals("http://h.example:8080/live/alice/s3cret/12.ts", creds.liveUrl(12, StreamContainer.TS))
        assertEquals("http://h.example:8080/movie/alice/s3cret/5.mkv", creds.vodUrl(5, "MKV"))
        assertEquals("http://h.example:8080/movie/alice/s3cret/5.mp4", creds.vodUrl(5, "../x"))
        assertEquals(
            "http://h.example:8080/streaming/timeshift.php?username=alice&password=s3cret&stream=7&start=2026-10-13:20-00&duration=60",
            creds.timeshiftUrl(7, 1791921600L, 60, ZoneOffset.UTC)
        )
    }

    @Test
    fun `authenticate tolerates string numbers`() = runBlocking {
        val http = FakeHttp(mapOf(
            "player_api.php?username=alice&password=s3cret" to """{"user_info":{"username":"alice","password":"s3cret","auth":1,"status":"Active","exp_date":"1800000000","is_trial":"0","active_cons":"1","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"timezone":"Europe/London","timestamp_now":1792008000}}"""
        ))
        val acc = XtreamApi(http, creds).authenticate()
        assertEquals(2, acc.maxConnections)
        assertEquals(1800000000L, acc.expiresAtEpoch)
        assertEquals("Europe/London", acc.timezone)
        assertEquals(listOf("m3u8", "ts"), acc.allowedFormats)
    }

    @Test
    fun `html reply gives a readable error without credentials`() = runBlocking {
        val http = FakeHttp(mapOf("player_api.php" to "<html><body>nginx</body></html>"))
        val e = assertFailsWith<XtreamException> { XtreamApi(http, creds).authenticate() }
        assertFalse(e.message!!.contains("s3cret"))
        assertTrue(e.message!!.contains("web page"))
    }

    @Test
    fun `refused login message is scrubbed`() = runBlocking {
        val http = FakeHttp(mapOf("player_api.php" to """{"user_info":{"auth":0,"status":"Expired","message":"account alice s3cret expired"}}"""))
        val e = assertFailsWith<XtreamException> { XtreamApi(http, creds).authenticate() }
        assertFalse(e.message!!.contains("s3cret"))
        assertTrue(e.message!!.contains(Redact.MASK))
    }

    @Test
    fun `live streams map with fallbacks`() = runBlocking {
        val http = FakeHttp(mapOf(
            "get_live_streams" to """[{"num":"7","name":"BBC One HD","stream_id":"101","stream_icon":"javascript:alert(1)","epg_channel_id":"bbc1.uk","category_id":"3","tv_archive":"1","tv_archive_duration":"7"},{"stream_id":102,"name":"No num"},{"name":"no id"}]"""
        ))
        val list = XtreamApi(http, creds).liveStreams()
        assertEquals(2, list.size)
        assertEquals(7, list[0].number)
        assertEquals(null, list[0].logo)
        assertTrue(list[0].archive)
        assertEquals(7, list[0].archiveDays)
        assertEquals(2, list[1].number)
    }

    @Test
    fun `categories as object keyed by index`() = runBlocking {
        val http = FakeHttp(mapOf("get_live_categories" to """{"0":{"category_id":"1","category_name":"UK"},"1":{"category_id":"2","category_name":"US","parent_id":"1"}}"""))
        val cats = XtreamApi(http, creds).liveCategories()
        assertEquals(listOf("UK", "US"), cats.map { it.name })
        assertEquals("1", cats[1].parentId)
    }

    @Test
    fun `series info episodes keyed by season or as arrays`() = runBlocking {
        val keyed = """{"info":{"name":"Harbour Lights","series_id":"9"},"episodes":{"1":[{"id":"501","episode_num":"2","title":"Two","container_extension":"mkv","info":{"duration_secs":"2700"}},{"id":"500","episode_num":1,"title":"One","season":1}],"2":[{"id":"600","episode_num":1,"title":"S2"}]}}"""
        val arr = """{"info":{"name":"X"},"episodes":[[{"id":"1","episode_num":1,"season":3,"title":"A"}]]}"""
        val api1 = XtreamApi(FakeHttp(mapOf("get_series_info" to keyed)), creds)
        val s = api1.seriesInfo(9)
        assertEquals(listOf(1, 2), s.seasons)
        assertEquals(listOf("One", "Two"), s.episodes[1]!!.map { it.title })
        assertEquals(2700L, s.episodes[1]!![1].durationSecs)
        val api2 = XtreamApi(FakeHttp(mapOf("get_series_info" to arr)), creds)
        assertEquals(listOf(3), api2.seriesInfo(1).seasons)
    }

    @Test
    fun `short epg decodes base64`() = runBlocking {
        val http = FakeHttp(mapOf("get_short_epg" to """{"epg_listings":[{"title":"RXZlbmluZyBOZXdz","description":"","start_timestamp":"1792008000","stop_timestamp":"1792011600","has_archive":"1"}]}"""))
        val e = XtreamApi(http, creds).shortEpg(101)
        assertEquals("Evening News", e[0].title)
        assertTrue(e[0].hasArchive)
    }
}
