package tv.sloopy.core.sports

import kotlinx.coroutines.runBlocking
import tv.sloopy.core.net.Http
import tv.sloopy.core.net.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoresTest {
    private class FakeHttp(val body: String) : Http {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse =
            HttpResponse(200, "application/json", body.length.toLong(), body.byteInputStream())
    }
    @Test fun `parses a live game with red zone and network`() = runBlocking {
        val body = """{"events":[{"id":"1","date":"2026-10-18T17:00Z","competitions":[{"date":"2026-10-18T17:00Z","competitors":[
          {"id":"10","homeAway":"home","score":"17","records":[{"summary":"4-1"}],"team":{"id":"4","displayName":"Cincinnati Bengals","abbreviation":"CIN"}},
          {"id":"11","homeAway":"away","score":"14","team":{"id":"5","displayName":"Cleveland Browns","abbreviation":"CLE"}}],
          "status":{"type":{"state":"in","shortDetail":"Q3 4:12"}},"situation":{"isRedZone":true,"possession":"10"},
          "broadcasts":[{"names":["CBS"]}]}]}]}"""
        val g = Scores(FakeHttp(body)).games("nfl")
        assertEquals(1, g.size)
        assertEquals("Cincinnati Bengals", g[0].home); assertEquals(17, g[0].homeScore); assertEquals("in", g[0].state)
        assertTrue(g[0].redZone); assertEquals("CIN", g[0].possession); assertEquals(listOf("CBS"), g[0].networks)
        assertEquals(1792342800L, g[0].startEpoch)
        assertEquals("4", g[0].homeId); assertEquals("4-1", g[0].homeRecord); assertTrue(g[0].has("nfl", "5"))
    }
    @Test fun `parses the team list`() = runBlocking {
        val body = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"4","displayName":"Cincinnati Bengals","abbreviation":"CIN","logos":[{"href":"https://x/cin.png"}]}},{"team":{"id":"5","displayName":"Cleveland Browns","abbreviation":"CLE"}}]}]}]}"""
        val t = Scores(FakeHttp(body)).teams("nfl")
        assertEquals(listOf("Cincinnati Bengals", "Cleveland Browns"), t.map { it.name }); assertEquals("https://x/cin.png", t[0].logo); assertEquals(null, t[1].logo)
    }
    @Test fun `parses standings with conferences and the right record per league`() = runBlocking {
        val body = """{"name":"NHL","children":[{"name":"Eastern Conference","standings":{"entries":[{"team":{"id":"1","displayName":"Boston Bruins","abbreviation":"BOS"},"stats":[{"name":"wins","displayValue":"6"},{"name":"losses","displayValue":"1"},{"name":"otLosses","displayValue":"1"},{"name":"points","displayValue":"13"}]}]}}]}"""
        val s = Scores(FakeHttp(body)).standings("nhl")
        assertEquals("Eastern Conference", s[0].name); assertEquals("6-1-1", s[0].rows[0].record); assertEquals("13 pts", s[0].rows[0].note)
        assertEquals("4-1", Scores.recordLine("nfl", mapOf("wins" to "4", "losses" to "1", "ties" to "0")))
        assertEquals("2.5 GB", Scores.noteLine("mlb", mapOf("gamesBehind" to "2.5")))
    }
    @Test fun `college standings use the overall record, not the last split`() = runBlocking {
        val body = """{"name":"CFB","children":[{"name":"American","standings":{"entries":[{"team":{"id":"1","displayName":"Memphis Tigers","abbreviation":"MEM"},"stats":[{"name":"wins","displayValue":"5","type":"wins"},{"name":"losses","displayValue":"1","type":"losses"},{"name":"overall","displayValue":"5-1","type":"total"},{"name":"vs. Conf.","displayValue":"2-0","type":"vsconf"},{"name":"wins","displayValue":"0","type":"vsusarankedteams_wins"},{"name":"losses","displayValue":"0","type":"vsusarankedteams_losses"}]}]}}]}"""
        val s = Scores(FakeHttp(body)).standings("college-football")
        assertEquals("5-1", s[0].rows[0].record); assertEquals("2-0 conf", s[0].rows[0].note)
    }
    @Test fun `bad body gives no games`() = runBlocking { assertEquals(0, Scores(FakeHttp("<html>")).games("nfl").size) }
}
