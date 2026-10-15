package tv.sloopy.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tv.sloopy.core.net.Http

data class Game(
    val id: String,
    val league: String,            // nfl | college-football | nba | mlb | nhl
    val home: String, val away: String,
    val homeShort: String, val awayShort: String,
    val homeScore: Int, val awayScore: Int,
    val state: String,             // pre | in | post
    val detail: String,            // "Q3 4:12", "Final", "Sun 1:00 PM"
    val redZone: Boolean,
    val possession: String?,       // abbreviation of the team with the ball
    val networks: List<String>,    // CBS, FOX, ESPN…
    val startEpoch: Long,
)

/** ESPN's public scoreboard feed: what every scores app reads. No key, no login. */
class Scores(private val http: Http) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun games(league: String, yyyymmdd: String? = null): List<Game> {
        val path = when (league) { "nfl" -> "football/nfl"; "college-football" -> "football/college-football"; "nba" -> "basketball/nba"; "mlb" -> "baseball/mlb"; "nhl" -> "hockey/nhl"; else -> return emptyList() }
        val extra = (if (yyyymmdd != null) "&dates=$yyyymmdd" else "") + (if (league == "college-football") "&groups=80&limit=200" else "")
        val text = http.get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?lang=en&region=us$extra", timeoutMs = 10_000).use { r ->
            if (!r.ok) return emptyList()
            r.body.readBytes().decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val events = (root["events"] as? JsonArray) ?: return emptyList()
        return events.mapNotNull { e -> runCatching { parse(e.jsonObject, league) }.getOrNull() }
    }

    private fun parse(e: JsonObject, league: String): Game? {
        val comp = (e["competitions"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val teams = (comp["competitors"] as? JsonArray)?.map { it.jsonObject } ?: return null
        val home = teams.firstOrNull { it.str("homeAway") == "home" } ?: return null
        val away = teams.firstOrNull { it.str("homeAway") == "away" } ?: return null
        val status = comp["status"]?.jsonObject ?: e["status"]?.jsonObject
        val type = status?.get("type")?.jsonObject
        val sit = comp["situation"] as? JsonObject
        val nets = (comp["broadcasts"] as? JsonArray)?.flatMap { b -> (b.jsonObject["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList() } ?: emptyList()
        val possId = sit?.str("possession")
        val possName = teams.firstOrNull { it.str("id") == possId }?.get("team")?.jsonObject?.str("abbreviation")
        val date = comp.str("date") ?: e.str("date")
        return Game(
            id = e.str("id") ?: return null, league = league,
            home = home["team"]?.jsonObject?.str("displayName") ?: "?", away = away["team"]?.jsonObject?.str("displayName") ?: "?",
            homeShort = home["team"]?.jsonObject?.str("abbreviation") ?: "", awayShort = away["team"]?.jsonObject?.str("abbreviation") ?: "",
            homeScore = home.str("score")?.toIntOrNull() ?: 0, awayScore = away.str("score")?.toIntOrNull() ?: 0,
            state = type?.str("state") ?: "pre", detail = type?.str("shortDetail") ?: "",
            redZone = (sit?.get("isRedZone") as? JsonPrimitive)?.booleanOrNull == true,
            possession = possName, networks = nets.map { it.uppercase() }.distinct(),
            startEpoch = date?.let { parseIso(it) } ?: 0L,
        )
    }

    private fun parseIso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s.replace("Z", "+00:00")).toEpochSecond() }.getOrDefault(0L)
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Words a network's channel name is expected to carry, for the fallback mapping. */
        val NETWORK_WORDS = mapOf(
            "CBS" to listOf("CBS"), "FOX" to listOf("FOX"), "NBC" to listOf("NBC"), "ABC" to listOf("ABC"), "ESPN" to listOf("ESPN"),
            "ESPN2" to listOf("ESPN2", "ESPN 2"), "ESPNU" to listOf("ESPNU", "ESPN U"), "NFL NET" to listOf("NFL NETWORK", "NFL NET"), "NFL NETWORK" to listOf("NFL NETWORK", "NFL NET"),
            "NFLN" to listOf("NFL NETWORK", "NFL NET"), "AMAZON" to listOf("PRIME VIDEO", "AMAZON"), "PRIME VIDEO" to listOf("PRIME VIDEO", "AMAZON"),
            "FS1" to listOf("FS1", "FOX SPORTS 1"), "FS2" to listOf("FS2", "FOX SPORTS 2"), "BTN" to listOf("BIG TEN", "BTN"), "SEC NETWORK" to listOf("SEC NETWORK", "SECN"), "SECN" to listOf("SEC NETWORK", "SECN"),
            "ACCN" to listOf("ACC NETWORK", "ACCN"), "ACC NETWORK" to listOf("ACC NETWORK", "ACCN"), "CBSSN" to listOf("CBS SPORTS", "CBSSN"), "CBS SPORTS NETWORK" to listOf("CBS SPORTS", "CBSSN"),
            "PEACOCK" to listOf("PEACOCK"), "ESPN+" to listOf("ESPN+", "ESPN PLUS"), "NBCSN" to listOf("NBCSN"), "TNT" to listOf("TNT"), "TBS" to listOf("TBS"), "USA" to listOf("USA NETWORK", "USA"),
            "NHL NETWORK" to listOf("NHL NETWORK"), "MLB NETWORK" to listOf("MLB NETWORK"), "NBA TV" to listOf("NBA TV"), "REDZONE" to listOf("REDZONE", "RED ZONE"), "NFL REDZONE" to listOf("REDZONE", "RED ZONE"),
        )
    }
}
