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
          {"id":"10","homeAway":"home","score":"17","team":{"displayName":"Cincinnati Bengals","abbreviation":"CIN"}},
          {"id":"11","homeAway":"away","score":"14","team":{"displayName":"Cleveland Browns","abbreviation":"CLE"}}],
          "status":{"type":{"state":"in","shortDetail":"Q3 4:12"}},"situation":{"isRedZone":true,"possession":"10"},
          "broadcasts":[{"names":["CBS"]}]}]}]}"""
        val g = Scores(FakeHttp(body)).games("nfl")
        assertEquals(1, g.size)
        assertEquals("Cincinnati Bengals", g[0].home); assertEquals(17, g[0].homeScore); assertEquals("in", g[0].state)
        assertTrue(g[0].redZone); assertEquals("CIN", g[0].possession); assertEquals(listOf("CBS"), g[0].networks)
        assertEquals(1792342800L, g[0].startEpoch)
    }
    @Test fun `bad body gives no games`() = runBlocking { assertEquals(0, Scores(FakeHttp("<html>")).games("nfl").size) }
}
