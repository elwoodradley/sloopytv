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
    val homeId: String = "", val awayId: String = "",          // ESPN team ids: what "my teams" matches on
    val homeRecord: String = "", val awayRecord: String = "",  // "4-1"
    val homeLogo: String? = null, val awayLogo: String? = null,
    val homeLoc: String = "", val awayLoc: String = "",        // "Michigan State": the school or city
    val homeMascot: String = "", val awayMascot: String = "",  // "Spartans"
) {
    fun has(league: String, teamId: String) = this.league == league && (homeId == teamId || awayId == teamId)
}

/** One team from ESPN's team list for a league. */
data class Team(val league: String, val id: String, val name: String, val abbr: String, val logo: String?)

/** One standings table (a conference or a division) with its rows in order. */
data class StandingsGroup(val name: String, val rows: List<StandingsRow>)
data class StandingsRow(val teamId: String, val team: String, val abbr: String, val record: String, val note: String)

/** ESPN's public scoreboard feed: what every scores app reads. No key, no login. */
class Scores(private val http: Http) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun games(league: String, yyyymmdd: String? = null): List<Game> {
        val path = path(league) ?: return emptyList()
        val extra = (if (yyyymmdd != null) "&dates=$yyyymmdd" else "") + (if (league == "college-football") "&groups=80&limit=200" else "")
        val text = http.get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?lang=en&region=us$extra", timeoutMs = 10_000).use { r ->
            if (!r.ok) return emptyList()
            r.body.readBytes().decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val events = (root["events"] as? JsonArray) ?: return emptyList()
        return events.mapNotNull { e -> runCatching { parse(e.jsonObject, league) }.getOrNull() }
    }

    /** Every team in a league: id, name, abbreviation, logo. College football is the top division only. */
    suspend fun teams(league: String): List<Team> {
        val path = path(league) ?: return emptyList()
        val extra = if (league == "college-football") "&groups=80" else ""
        val text = http.get("https://site.api.espn.com/apis/site/v2/sports/$path/teams?limit=500$extra", timeoutMs = 15_000).use { r ->
            if (!r.ok) return emptyList()
            r.body.readBytes().decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val list = (root["sports"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("leagues")?.let { it as? JsonArray }?.firstOrNull()?.jsonObject?.get("teams") as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val t = (e as? JsonObject)?.get("team")?.jsonObject ?: return@mapNotNull null
            val id = t.str("id") ?: return@mapNotNull null
            Team(league, id, t.str("displayName") ?: return@mapNotNull null, t.str("abbreviation") ?: "", (t["logos"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("href"))
        }.sortedBy { it.name }
    }

    /** The league table(s): conferences for NFL/NBA/NHL/MLB, the top-25-style poll is not here. */
    suspend fun standings(league: String): List<StandingsGroup> {
        val path = path(league) ?: return emptyList()
        val text = http.get("https://site.api.espn.com/apis/v2/sports/$path/standings", timeoutMs = 15_000).use { r ->
            if (!r.ok) return emptyList()
            r.body.readBytes().decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = ArrayList<StandingsGroup>()
        fun walk(node: JsonObject, name: String) {
            val kids = node["children"] as? JsonArray
            if (kids != null && kids.isNotEmpty()) { kids.forEach { k -> (k as? JsonObject)?.let { walk(it, it.str("name") ?: name) } }; return }
            val entries = node["standings"]?.jsonObject?.get("entries") as? JsonArray ?: return
            val rows = entries.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val t = o["team"]?.jsonObject ?: return@mapNotNull null
                // College tables repeat every stat per split (home, away, vs conference) under the same
                // name, so the first occurrence is the overall one; a "total" typed stat is the record itself.
                val all = (o["stats"] as? JsonArray)?.mapNotNull { st -> val so = st as? JsonObject ?: return@mapNotNull null; Triple(so.str("name") ?: return@mapNotNull null, so.str("type") ?: "", so.str("displayValue") ?: so.str("value") ?: "") } ?: emptyList()
                val stats = LinkedHashMap<String, String>()
                all.forEach { (n, _, v) -> stats.putIfAbsent(n, v) }
                all.firstOrNull { it.second == "total" }?.let { stats["overall"] = it.third }
                all.firstOrNull { it.second == "vsconf" }?.let { stats["vsconf"] = it.third }
                StandingsRow(t.str("id") ?: "", t.str("displayName") ?: "?", t.str("abbreviation") ?: "", recordLine(league, stats), noteLine(league, stats))
            }
            if (rows.isNotEmpty()) out.add(StandingsGroup(name, rows))
        }
        walk(root, root.str("name") ?: league)
        return out
    }

    private fun path(league: String): String? = when (league) { "nfl" -> "football/nfl"; "college-football" -> "football/college-football"; "nba" -> "basketball/nba"; "mlb" -> "baseball/mlb"; "nhl" -> "hockey/nhl"; else -> null }

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
            homeLoc = home["team"]?.jsonObject?.str("location") ?: "", awayLoc = away["team"]?.jsonObject?.str("location") ?: "",
            homeMascot = home["team"]?.jsonObject?.str("name") ?: "", awayMascot = away["team"]?.jsonObject?.str("name") ?: "",
            homeShort = home["team"]?.jsonObject?.str("abbreviation") ?: "", awayShort = away["team"]?.jsonObject?.str("abbreviation") ?: "",
            homeScore = home.str("score")?.toIntOrNull() ?: 0, awayScore = away.str("score")?.toIntOrNull() ?: 0,
            state = type?.str("state") ?: "pre", detail = type?.str("shortDetail") ?: "",
            redZone = (sit?.get("isRedZone") as? JsonPrimitive)?.booleanOrNull == true,
            possession = possName, networks = nets.map { it.uppercase() }.distinct(),
            startEpoch = date?.let { parseIso(it) } ?: 0L,
            homeId = home["team"]?.jsonObject?.str("id") ?: "", awayId = away["team"]?.jsonObject?.str("id") ?: "",
            homeRecord = (home["records"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("summary") ?: "", awayRecord = (away["records"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("summary") ?: "",
            homeLogo = home["team"]?.jsonObject?.str("logo"), awayLogo = away["team"]?.jsonObject?.str("logo"),
        )
    }

    private fun parseIso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s.replace("Z", "+00:00")).toEpochSecond() }.getOrDefault(0L)
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        val LEAGUES = listOf("nfl", "college-football", "nba", "nhl", "mlb")
        fun leagueName(league: String) = when (league) { "nfl" -> "NFL"; "college-football" -> "College football"; "nba" -> "NBA"; "nhl" -> "NHL"; "mlb" -> "MLB"; else -> league.uppercase() }
        /** "4-1", "6-1-1" (NHL adds overtime losses). */
        fun recordLine(league: String, stats: Map<String, String>): String {
            // The "overall" stat is "5-1" for college and "6-1-1, 13 PTS" for hockey: the record is before the comma.
            stats["overall"]?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val w = stats["wins"] ?: "0"; val l = stats["losses"] ?: "0"
            val extra = when (league) { "nhl" -> stats["otLosses"]?.let { "-$it" } ?: ""; "nfl" -> stats["ties"]?.takeIf { it != "0" }?.let { "-$it" } ?: ""; else -> "" }
            return "$w-$l$extra"
        }
        /** What else the table says about the row: points for hockey, games behind for the others. */
        fun noteLine(league: String, stats: Map<String, String>): String = when (league) {
            "nhl" -> stats["points"]?.let { "$it pts" } ?: ""
            "nfl" -> stats["streak"] ?: ""
            "college-football" -> stats["vsconf"]?.let { "$it conf" } ?: (stats["streak"] ?: "")
            else -> stats["gamesBehind"]?.let { if (it == "-" || it == "0") "" else "$it GB" } ?: ""
        }
        /** Words a network's channel name is expected to carry, for the fallback mapping. */
        /**
         * How well a guide title names a game: 2 when both teams are in it, 1 for one, 0 for
         * none. A team counts by its short name ("Michigan State") or its mascot ("Spartans");
         * two-letter leftovers and the bare word "state" never count on their own.
         */
        fun mentions(title: String, g: Game): Int {
            val t = " " + title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
            fun team(full: String, loc: String, mascot: String): Boolean {
                val l = loc.ifBlank { full.substringBeforeLast(' ', "") }.lowercase().trim()
                val m = mascot.ifBlank { full.substringAfterLast(' ') }.lowercase().trim()
                val names = listOf(l, m).filter { it.length >= 3 && it != "state" }
                return names.any { t.contains(" $it ") }
            }
            return (if (team(g.home, g.homeLoc, g.homeMascot)) 1 else 0) + (if (team(g.away, g.awayLoc, g.awayMascot)) 1 else 0)
        }

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
