package tv.sloopy.core.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import tv.sloopy.core.Redact
import tv.sloopy.core.model.Account
import tv.sloopy.core.model.Category
import tv.sloopy.core.model.Channel
import tv.sloopy.core.model.Episode
import tv.sloopy.core.model.Series
import tv.sloopy.core.model.SeriesInfo
import tv.sloopy.core.model.ShortEpg
import tv.sloopy.core.model.StreamContainer
import tv.sloopy.core.model.Vod
import tv.sloopy.core.model.VodInfo
import tv.sloopy.core.net.Http
import tv.sloopy.core.net.HttpException
import tv.sloopy.core.net.Limits
import tv.sloopy.core.net.readAllCapped
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Validated, normalised base URL plus credentials. Never log this object. */
class XtreamCredentials private constructor(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    val isPlainHttp: Boolean get() = baseUrl.startsWith("http://")

    companion object {
        /**
         * Accepts what people paste: "host:8080", "http://host:8080/", a full
         * "http://host/get.php?username=..&password=..", or a player_api URL.
         */
        fun parse(rawUrl: String, rawUser: String?, rawPass: String?): XtreamCredentials {
            var text = rawUrl.trim()
            if (text.isEmpty()) throw XtreamException("Server address is empty")
            if (!text.contains("://")) text = "http://$text"
            val uri = try { URI(text) } catch (e: Exception) { throw XtreamException("Server address is not a valid URL") }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw XtreamException("Only http and https servers are supported")
            val host = uri.host ?: throw XtreamException("Server address has no host")
            if (host.isBlank()) throw XtreamException("Server address has no host")

            var user = rawUser?.trim().orEmpty()
            var pass = rawPass?.trim().orEmpty()
            // Credentials embedded in a pasted get.php / player_api URL.
            uri.rawQuery?.split('&')?.forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) {
                    val k = kv.substring(0, i).lowercase()
                    val v = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                    if (k == "username" && user.isEmpty()) user = v
                    if (k == "password" && pass.isEmpty()) pass = v
                }
            }
            if (user.isEmpty() || pass.isEmpty()) throw XtreamException("Username and password are required")
            if (user.length > 256 || pass.length > 256) throw XtreamException("Username or password is too long")

            val port = if (uri.port > 0) ":${uri.port}" else ""
            val base = "$scheme://$host$port"
            Redact.register(user, pass)
            return XtreamCredentials(base, user, pass)
        }
    }

    private fun q(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun p(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    internal fun api(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("$baseUrl/player_api.php?username=${q(username)}&password=${q(password)}")
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(q(v)) }
        return sb.toString()
    }

    fun xmltvUrl(): String = "$baseUrl/xmltv.php?username=${q(username)}&password=${q(password)}"

    fun liveUrl(streamId: Long, container: StreamContainer): String =
        "$baseUrl/live/${p(username)}/${p(password)}/$streamId.${container.ext}"

    fun vodUrl(streamId: Long, ext: String): String =
        "$baseUrl/movie/${p(username)}/${p(password)}/$streamId.${safeExt(ext)}"

    fun seriesUrl(episodeId: Long, ext: String): String =
        "$baseUrl/series/${p(username)}/${p(password)}/$episodeId.${safeExt(ext)}"

    /** Catch-up. [startEpoch] in seconds, [durationMin] in minutes. Xtream wants local server time as YYYY-MM-DD:HH-MM. */
    fun timeshiftUrl(streamId: Long, startEpoch: Long, durationMin: Int, zoneId: java.time.ZoneId): String {
        val t = java.time.Instant.ofEpochSecond(startEpoch).atZone(zoneId)
        val start = "%04d-%02d-%02d:%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        return "$baseUrl/streaming/timeshift.php?username=${q(username)}&password=${q(password)}&stream=$streamId&start=$start&duration=$durationMin"
    }

    private fun safeExt(ext: String): String {
        val e = ext.trim().lowercase().trimStart('.')
        return if (e.matches(Regex("[a-z0-9]{1,5}"))) e else "mp4"
    }
}

/**
 * Xtream Codes "player_api" client. Pure Kotlin; HTTP is injected.
 * Every error message that could carry a URL is passed through [Redact].
 */
class XtreamApi(private val http: Http, private val creds: XtreamCredentials) {

    private suspend fun fetch(url: String, limit: Long = Limits.JSON_BYTES): JsonElement = withContext(Dispatchers.IO) {
        val resp = try {
            http.get(url, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT))
        } catch (e: IOException) {
            throw XtreamException("Could not reach the server: ${Redact.apply(e.message)}", e)
        }
        resp.use {
            if (!it.ok) throw HttpException(it.code, "Server answered HTTP ${it.code}")
            val bytes = it.readAllCapped(limit)
            if (bytes.isEmpty()) throw XtreamException("Server sent an empty reply")
            // A panel that is down or a captive portal returns HTML. Say so instead of a JSON stack trace.
            val head = bytes.take(32).toByteArray().decodeToString().trimStart()
            if (head.startsWith("<")) throw XtreamException("Server sent a web page instead of data. Check the address and port.")
            try {
                parseJson(bytes)
            } catch (e: Exception) {
                throw XtreamException("Server sent data the app could not read", e)
            }
        }
    }

    suspend fun authenticate(): Account {
        val root = fetch(creds.api()).requireObject("account")
        val user = root["user_info"].obj ?: throw XtreamException("Server did not return account details")
        val server = root["server_info"].obj
        val auth = user.bool("auth") || user.str("status").equals("Active", ignoreCase = true)
        if (!auth) {
            val msg = user.str("message") ?: user.str("status") ?: "Login refused"
            throw XtreamException(Redact.apply(msg))
        }
        return Account(
            username = creds.username,
            status = user.str("status") ?: "Unknown",
            expiresAtEpoch = user.long("exp_date")?.takeIf { it > 0 },
            isTrial = user.bool("is_trial"),
            activeConnections = user.int("active_cons") ?: 0,
            maxConnections = user.int("max_connections") ?: 0,
            allowedFormats = user.strList("allowed_output_formats"),
            serverTimeEpoch = server.long("timestamp_now"),
            timezone = server.str("timezone"),
            serverUrl = server.str("url"),
            httpsPort = server.int("https_port"),
        )
    }

    suspend fun liveCategories(): List<Category> = categories("get_live_categories")
    suspend fun vodCategories(): List<Category> = categories("get_vod_categories")
    suspend fun seriesCategories(): List<Category> = categories("get_series_categories")

    private suspend fun categories(action: String): List<Category> =
        fetch(creds.api(action)).asList().mapNotNull { o ->
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id, (o.str("category_name") ?: "Unnamed").clip(200), o.str("parent_id")?.takeIf { it != "0" })
        }.distinctBy { it.id }

    suspend fun liveStreams(categoryId: String? = null): List<Channel> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        val list = fetch(creds.api("get_live_streams", extra)).asList()
        if (list.size > Limits.MAX_CHANNELS) throw XtreamException("Provider returned more channels than the app allows")
        var fallbackNum = 0
        return list.mapNotNull { o ->
            val id = o.long("stream_id") ?: return@mapNotNull null
            fallbackNum++
            Channel(
                id = id,
                number = o.int("num") ?: fallbackNum,
                name = (o.str("name") ?: "Channel $id").clip(200),
                logo = o.str("stream_icon")?.let(::safeImageUrl),
                categoryId = o.str("category_id"),
                epgId = o.str("epg_channel_id")?.clip(200),
                archive = o.bool("tv_archive"),
                archiveDays = o.int("tv_archive_duration") ?: 0,
            )
        }.distinctBy { it.id }
    }

    suspend fun vodStreams(categoryId: String? = null): List<Vod> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return fetch(creds.api("get_vod_streams", extra)).asList().mapNotNull { o ->
            val id = o.long("stream_id") ?: return@mapNotNull null
            Vod(
                id = id,
                number = o.int("num") ?: 0,
                name = (o.str("name") ?: "Movie $id").clip(300),
                poster = o.str("stream_icon")?.let(::safeImageUrl),
                categoryId = o.str("category_id"),
                container = o.str("container_extension") ?: "mp4",
                rating = o.double("rating"),
                addedEpoch = o.long("added"),
                year = o.str("year") ?: o.str("releasedate")?.take(4),
            )
        }.distinctBy { it.id }
    }

    suspend fun vodInfo(vodId: Long): VodInfo {
        val root = fetch(creds.api("get_vod_info", mapOf("vod_id" to vodId.toString()))).requireObject("movie")
        val info = root["info"].obj
        val data = root["movie_data"].obj
        return VodInfo(
            id = data.long("stream_id") ?: vodId,
            name = (data.str("name") ?: info.str("name") ?: "Movie").clip(300),
            plot = (info.str("plot") ?: info.str("description"))?.clip(Limits.MAX_TEXT),
            cast = info.str("cast")?.clip(1000),
            director = info.str("director")?.clip(300),
            genre = info.str("genre")?.clip(300),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            durationSecs = info.long("duration_secs"),
            backdrop = info.firstStr("backdrop_path")?.let(::safeImageUrl),
            poster = (info.str("movie_image") ?: info.str("cover_big"))?.let(::safeImageUrl),
            trailer = info.str("youtube_trailer"),
            container = data.str("container_extension") ?: "mp4",
            rating = info.double("rating"),
        )
    }

    suspend fun seriesList(categoryId: String? = null): List<Series> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return fetch(creds.api("get_series", extra)).asList().mapNotNull { o -> parseSeries(o) }.distinctBy { it.id }
    }

    private fun parseSeries(o: kotlinx.serialization.json.JsonObject, fallbackId: Long? = null): Series? {
        val id = o.long("series_id") ?: fallbackId ?: return null
        return Series(
            id = id,
            number = o.int("num") ?: 0,
            name = (o.str("name") ?: o.str("title") ?: "Series $id").clip(300),
            cover = o.str("cover")?.let(::safeImageUrl),
            categoryId = o.str("category_id"),
            plot = o.str("plot")?.clip(Limits.MAX_TEXT),
            genre = o.str("genre")?.clip(300),
            releaseDate = o.str("releaseDate") ?: o.str("release_date") ?: o.str("releasedate"),
            rating = o.double("rating"),
            backdrop = o.firstStr("backdrop_path")?.let(::safeImageUrl),
            lastModifiedEpoch = o.long("last_modified"),
        )
    }

    suspend fun seriesInfo(seriesId: Long): SeriesInfo {
        val root = fetch(creds.api("get_series_info", mapOf("series_id" to seriesId.toString()))).requireObject("series")
        val series = parseSeries(root["info"].obj ?: kotlinx.serialization.json.JsonObject(emptyMap()), seriesId)!!
        val episodesByKey = root["episodes"]
        val episodes = LinkedHashMap<Int, MutableList<Episode>>()
        fun add(ep: kotlinx.serialization.json.JsonObject, seasonHint: Int?) {
            val id = ep.long("id") ?: return
            val season = ep.int("season") ?: seasonHint ?: 1
            val info = ep["info"].obj
            episodes.getOrPut(season) { mutableListOf() }.add(
                Episode(
                    id = id,
                    season = season,
                    number = ep.int("episode_num") ?: (episodes[season]?.size ?: 0) + 1,
                    title = (ep.str("title") ?: "Episode").clip(300),
                    container = ep.str("container_extension") ?: "mp4",
                    image = info.str("movie_image")?.let(::safeImageUrl),
                    plot = info.str("plot")?.clip(Limits.MAX_TEXT),
                    durationSecs = info.long("duration_secs"),
                    airDate = info.str("releasedate") ?: info.str("air_date"),
                )
            )
        }
        when (episodesByKey) {
            is kotlinx.serialization.json.JsonObject -> episodesByKey.forEach { (k, v) -> v.asList().forEach { add(it, k.toIntOrNull()) } }
            is kotlinx.serialization.json.JsonArray -> episodesByKey.forEach { v -> v.asList().forEach { add(it, null) } }
            else -> {}
        }
        episodes.values.forEach { l -> l.sortBy { it.number } }
        val seasons = episodes.keys.sorted()
        return SeriesInfo(series, seasons, episodes.toSortedMap())
    }

    /** Now/next for one channel, for providers whose XMLTV is missing or stale. */
    suspend fun shortEpg(streamId: Long, limit: Int = 4): List<ShortEpg> {
        val root = fetch(creds.api("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())))
        val list = root.obj?.get("epg_listings").asList()
        return list.mapNotNull { o ->
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val stop = o.long("stop_timestamp") ?: return@mapNotNull null
            ShortEpg(
                title = (o.str("title")?.let(::b64) ?: "").clip(300),
                description = o.str("description")?.let(::b64)?.clip(Limits.MAX_TEXT),
                startEpoch = start, stopEpoch = stop,
                hasArchive = o.bool("has_archive"),
            )
        }
    }

    private fun b64(s: String): String = try {
        Base64.getDecoder().decode(s).decodeToString()
    } catch (e: Exception) { s }

    companion object {
        const val USER_AGENT = "Sloopy/1.0"

        /** Only http(s) image URLs are ever handed to the image loader. */
        fun safeImageUrl(u: String): String? {
            val t = u.trim()
            return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t.clip(2048) else null
        }
    }
}
