package tv.sloopy.core.sports

/**
 * Ranks a provider's channels as carriers of a game's network. A broadcast network (ABC, CBS,
 * FOX, NBC, CW) is really a hundred local affiliates, and only the local ones show the games:
 * "FOX 28 Columbus OH" has the Sunday game, "FOX SOUL" and the bare national "FOX" do not.
 * The viewer's rule (2026-10-17): a name with a city and a state carries games; a name that is
 * only the network, or only a state, is the national feed with the news and the talk shows.
 * Pure string work so it is testable; the view model applies it to channel keys.
 */
object Affiliates {
    /** Channels that carry the network's word but are never where the game is. */
    private val NOT_THIS: Map<String, List<String>> = mapOf(
        "FOX" to listOf("SOUL", "NEWS", "BUSINESS", "WEATHER", "NATION", "DEPORTES", "LIFE", "MOVIES", "CRIME", "CLASSICS", "SPORTS", "FS1", "FS2", "FXX", "FXM"),
        "ABC" to listOf("NEWS", "AUSTRALIA", "KIDS", "FAMILY", "COMEDY", "ME"),
        "NBC" to listOf("NEWS", "UNIVERSO", "GOLF", "NBCSN", "SPORTS", "LX", "NOW"),
        "CBS" to listOf("NEWS", "SPORTS", "REALITY", "DRAMA", "JUSTICE", "CBSN"),
        "CW" to listOf("SEED"),
        "ESPN" to listOf("NEWS", "DEPORTES", "CLASSIC"),
        "USA" to listOf("TODAY"),
    )

    val STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO",
        "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC",
    )
    private val STATE_NAMES = setOf(
        "ALABAMA", "ALASKA", "ARIZONA", "ARKANSAS", "CALIFORNIA", "COLORADO", "CONNECTICUT", "DELAWARE", "FLORIDA", "GEORGIA", "HAWAII", "IDAHO", "ILLINOIS",
        "INDIANA", "IOWA", "KANSAS", "KENTUCKY", "LOUISIANA", "MAINE", "MARYLAND", "MASSACHUSETTS", "MICHIGAN", "MINNESOTA", "MISSISSIPPI", "MISSOURI", "MONTANA",
        "NEBRASKA", "NEVADA", "HAMPSHIRE", "JERSEY", "MEXICO", "YORK", "CAROLINA", "DAKOTA", "OHIO", "OKLAHOMA", "OREGON", "PENNSYLVANIA", "ISLAND", "TENNESSEE",
        "TEXAS", "UTAH", "VERMONT", "VIRGINIA", "WASHINGTON", "WISCONSIN", "WYOMING",
    )
    private val NOISE = setOf("HD", "FHD", "UHD", "4K", "SD", "US", "USA", "EAST", "WEST", "E", "W", "TV", "NETWORK", "CHANNEL", "FEED", "LOCAL", "LOCALS", "NEW", "NORTH", "SOUTH", "ST", "FT")
    private val callSign = Regex("^[WK][A-Z]{2,3}(-?(TV|DT))?$")
    private val number = Regex("^\\d{1,3}$")

    /**
     * Rank for [key] (an upper-case channel key, country prefix removed) as a carrier of
     * [network]; null when the name says it is something else. Higher is better:
     * 4 city and state, 3 call sign or city, 2 bare network, 1 state only.
     */
    fun rank(network: String, key: String, words: List<String> = listOf(network)): Int? {
        var tokens = key.uppercase().split(' ', '-', '_', '.', ',', '(', ')', '|', '/').filter { it.isNotBlank() }
        val net = words.map { it.uppercase() }
        // `Dupes.key` keeps the provider's country prefix as the first token ("US FOX 28 ...");
        // a short all-letter first token that is not the network is that prefix, not a city.
        val first = tokens.firstOrNull()
        if (first != null && first.length in 2..3 && first.all { it.isLetter() } && net.none { it.split(' ').first() == first }) tokens = tokens.drop(1)
        val hit = net.any { w -> val ws = w.split(' '); tokens.windowed(ws.size, partialWindows = false).any { it == ws } }
        if (!hit) return null
        val bad = NOT_THIS[network.uppercase()] ?: emptyList()
        if (tokens.any { it in bad }) return null
        val netTokens = net.flatMap { it.split(' ') }.toSet()
        val rest = tokens.filter { it !in netTokens && it !in NOISE && !number.matches(it) }
        val state = rest.any { it in STATES || it in STATE_NAMES }
        val sign = rest.any { callSign.matches(it) }
        val city = rest.any { it !in STATES && it !in STATE_NAMES && !callSign.matches(it) }
        return when {
            city && state -> 4
            sign || city -> 3
            state -> 1
            else -> 2
        }
    }
}
