package tv.sloopy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.sloopy.TLog
import tv.sloopy.core.epg.XmltvChannel
import tv.sloopy.core.epg.XmltvParser
import tv.sloopy.core.m3u.M3uParser
import tv.sloopy.core.model.Account
import tv.sloopy.core.model.Programme
import tv.sloopy.core.model.StreamContainer
import tv.sloopy.core.net.Limits
import tv.sloopy.core.net.LimitedInputStream
import tv.sloopy.core.xtream.XtreamApi
import tv.sloopy.data.db.CategoryEntity
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.EpgChannelEntity
import tv.sloopy.data.db.MetaEntity
import tv.sloopy.data.db.NowNext
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.data.db.SeriesEntity
import tv.sloopy.data.db.SloopyDb
import tv.sloopy.data.db.VodEntity
import java.io.IOException
import java.time.ZoneId

sealed class RefreshState {
    data object Idle : RefreshState()
    data class Running(val step: String, val count: Int = 0) : RefreshState()
    data class Failed(val message: String) : RefreshState()
    data class Done(val channels: Int, val programmes: Int) : RefreshState()
}

/**
 * The provider cache: channels, categories, guide, library. One instance per app.
 * Everything heavy runs on IO; refreshes are serialised per provider.
 */
/** The key guide rows are stored under: the provider's EPG id, else one made from the stream id. */
fun channelEpgKey(c: ChannelEntity): String = c.epgId ?: "xc:${c.id}"

class Catalog(private val db: SloopyDb, private val providers: ProviderStore, private val prefs: Prefs) {
    private val lock = Mutex()
    private val _refresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refresh: StateFlow<RefreshState> = _refresh
    private val _epgNote = kotlinx.coroutines.flow.MutableStateFlow("")
    /** What happened on the last guide-file attempt, for the guide header: which identity got through, or every answer. */
    val epgNote: StateFlow<String> = _epgNote

    private val gens = HashMap<String, Int>()

    suspend fun epgGen(p: String): Int = gens[p] ?: (db.meta().get(p, "epgGen")?.toIntOrNull() ?: 0).also { gens[p] = it }

    // ---- Refresh -----------------------------------------------------------------------------

    /** Test credentials without touching the cache. */
    suspend fun testXtream(record: ProviderRecord): Account = withContext(Dispatchers.IO) {
        XtreamApi(AndroidHttp.core, record.xtream()).authenticate()
    }

    /** Full refresh: categories + channels (+ movies, series for Xtream) and then the guide. */
    suspend fun refreshAll(record: ProviderRecord, includeLibrary: Boolean = true, includeEpg: Boolean = true) = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                _refresh.value = RefreshState.Running("Connecting")
                var channels = 0
                when (record.kind) {
                    ProviderKind.XTREAM -> channels = refreshXtream(record, includeLibrary)
                    ProviderKind.M3U -> channels = refreshM3u(record)
                    ProviderKind.DEMO -> channels = refreshDemo()
                }
                prefs.setRefreshedAt(record.id, "channels")
                var programmes = 0
                if (includeEpg) programmes = refreshEpg(record)
                _refresh.value = RefreshState.Done(channels, programmes)
            } catch (e: Exception) {
                TLog.e(e) { "refresh failed for ${record.displayHost()}" }
                _refresh.value = RefreshState.Failed(TLog.userMessage(e))
                throw e
            }
        }
    }

    private suspend fun refreshXtream(record: ProviderRecord, includeLibrary: Boolean): Int {
        val api = XtreamApi(AndroidHttp.core, record.xtream())
        _refresh.value = RefreshState.Running("Channel groups")
        val cats = api.liveCategories()
        db.categories().replaceKind(record.id, "live", cats.mapIndexed { i, c -> CategoryEntity(record.id, "live", c.id, tv.sloopy.core.model.Names.clean(c.name), c.parentId, i) })
        _refresh.value = RefreshState.Running("Channels")
        val live = api.liveStreams()
        val catOrder = cats.mapIndexed { i, c -> c.id to i }.toMap()
        val sorted = live.sortedWith(compareBy({ catOrder[it.categoryId] ?: Int.MAX_VALUE }, { it.number }))
        db.channels().replaceAll(record.id, sorted.mapIndexed { i, c ->
            ChannelEntity(record.id, c.id, c.number, tv.sloopy.core.model.Names.clean(c.name), c.logo, c.categoryId, c.epgId?.ifBlank { null }, c.archive, c.archiveDays, null, i)
        })
        _refresh.value = RefreshState.Running("Channels", live.size)
        if (includeLibrary) {
            try {
                _refresh.value = RefreshState.Running("Movies")
                val vcats = api.vodCategories()
                db.categories().replaceKind(record.id, "vod", vcats.mapIndexed { i, c -> CategoryEntity(record.id, "vod", c.id, c.name, c.parentId, i) })
                val vods = api.vodStreams()
                db.vod().replaceAll(record.id, vods.map { v -> VodEntity(record.id, v.id, v.number, v.name, v.poster, v.categoryId, v.container, v.rating, v.addedEpoch, v.year) })
                _refresh.value = RefreshState.Running("Series")
                val scats = api.seriesCategories()
                db.categories().replaceKind(record.id, "series", scats.mapIndexed { i, c -> CategoryEntity(record.id, "series", c.id, c.name, c.parentId, i) })
                val series = api.seriesList()
                db.series().replaceAll(record.id, series.map { s -> SeriesEntity(record.id, s.id, s.number, s.name, s.cover, s.categoryId, s.plot, s.genre, s.releaseDate, s.rating, s.backdrop, s.lastModifiedEpoch) })
                prefs.setRefreshedAt(record.id, "library")
            } catch (e: Exception) {
                // Library is optional; a provider with live only must still work.
                TLog.w { "library refresh failed: ${TLog.userMessage(e)}" }
            }
        }
        return live.size
    }

    private suspend fun refreshM3u(record: ProviderRecord): Int {
        _refresh.value = RefreshState.Running("Playlist")
        val entries = AndroidHttp.core.get(record.baseUrl).use { r ->
            if (!r.ok) throw IOException("Playlist server answered HTTP ${r.code}")
            M3uParser.parse(r.body)
        }
        val channels = M3uParser.toChannels(entries)
        val groups = channels.mapNotNull { it.categoryId }.distinct()
        db.categories().replaceKind(record.id, "live", groups.mapIndexed { i, g -> CategoryEntity(record.id, "live", g, tv.sloopy.core.model.Names.clean(g), null, i) })
        db.channels().replaceAll(record.id, channels.mapIndexed { i, c ->
            ChannelEntity(record.id, c.id, c.number, tv.sloopy.core.model.Names.clean(c.name), c.logo, c.categoryId, c.epgId, c.archive, c.archiveDays, c.directUrl, i)
        })
        return channels.size
    }

    private suspend fun refreshDemo(): Int {
        db.categories().replaceKind(Demo.PROVIDER_ID, "live", Demo.categories())
        db.channels().replaceAll(Demo.PROVIDER_ID, Demo.channels())
        return Demo.channels().size
    }

    /** Downloads and imports the XMLTV guide. Safe to call often; it is a no-op when fresh. */
    suspend fun refreshEpg(record: ProviderRecord, force: Boolean = false, minIntervalMs: Long = EPG_MIN_INTERVAL_MS): Int = withContext(Dispatchers.IO) {
        val age = System.currentTimeMillis() - prefs.refreshedAt(record.id, "epg")
        if (!force && age < minIntervalMs && db.programmes().count(record.id, epgGen(record.id)) > 0) return@withContext 0
        _refresh.value = RefreshState.Running("Guide")
        val newGen = epgGen(record.id) + 1
        val count: Int
        try {
            count = when (record.kind) {
                ProviderKind.DEMO -> importProgrammes(record.id, newGen, Demo.programmes(System.currentTimeMillis() / 1000), emptyList())
                else -> {
                    val url = when (record.kind) {
                        ProviderKind.XTREAM -> record.xtream().xmltvUrl()
                        else -> record.epgUrl ?: return@withContext 0
                    }
                    importXmltv(record.id, newGen, url)
                }
            }
        } catch (e: Exception) {
            db.programmes().deleteGen(record.id, newGen)
            throw e
        }
        db.meta().set(MetaEntity(record.id, "epgGen", newGen.toString()))
        gens[record.id] = newGen
        db.programmes().deleteOtherGens(record.id, newGen)
        resolveEpgIds(record.id)
        prefs.setRefreshedAt(record.id, "epg")
        count
    }

    private suspend fun importXmltv(p: String, gen: Int, url: String): Int {
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        val chans = ArrayList<EpgChannelEntity>(BATCH)
        var total = 0
        val dao = db.programmes()
        val cdao = db.epgChannels()
        db.epgChannels().deleteAll(p)
        val sink = object : XmltvParser.Sink {
            override fun channel(c: XmltvChannel) {
                chans.add(EpgChannelEntity(p, c.id, c.name, c.icon, normalise(c.name)))
                if (chans.size >= BATCH) { cdao.insertBatchBlocking(chans); chans.clear() }
            }
            override fun programme(pr: Programme) {
                batch.add(pr.toEntity(p, gen))
                if (batch.size >= BATCH) {
                    dao.insertBatchBlocking(batch); total += batch.size; batch.clear()
                    _refresh.value = RefreshState.Running("Guide", total)
                }
            }
        }
        // Some panels refuse xmltv.php to a player they do not recognise (that HTTP 451 or 403) but
        // hand it to the ones they know. Try the agent that worked last time, then the usual ones.
        val remembered = prefs.epgUserAgent(p)
        val agents = (listOfNotNull(remembered) + EPG_AGENTS).distinct()
        var lastCode = 0
        var got = false
        val answers = ArrayList<String>()
        for (ua in agents) {
            val name = EPG_AGENT_NAMES[EPG_AGENTS.indexOf(ua).coerceAtLeast(0)]
            val r = try { AndroidHttp.core.get(url, headers = mapOf("User-Agent" to ua), timeoutMs = 120_000) } catch (e: IOException) { answers.add("$name: no answer"); lastCode = 0; continue }
            if (!r.ok) { lastCode = r.code; r.close(); answers.add("$name: HTTP ${r.code}"); if (r.code in listOf(401, 403, 404, 406, 429, 451)) continue else break }
            r.use { XmltvParser(parserFactory = { android.util.Xml.newPullParser() }, defaultZone = ZoneId.systemDefault()).parse(LimitedInputStream(it.body, Limits.XMLTV_BYTES), sink) }
            if (ua != remembered) prefs.setEpgUserAgent(p, ua)
            _epgNote.value = "Guide file accepted as $name"
            TLog.i { "guide file accepted as $name" }
            got = true
            break
        }
        if (!got) { if (remembered != null) prefs.setEpgUserAgent(p, null); _epgNote.value = answers.joinToString(" · "); throw IOException("Guide server answered HTTP $lastCode") }
        if (chans.isNotEmpty()) cdao.insertBatchBlocking(chans)
        if (batch.isNotEmpty()) { dao.insertBatchBlocking(batch); total += batch.size }
        return total
    }

    private fun importProgrammes(p: String, gen: Int, list: List<Programme>, chans: List<EpgChannelEntity>): Int {
        if (chans.isNotEmpty()) db.epgChannels().insertBatchBlocking(chans)
        list.chunked(BATCH).forEach { db.programmes().insertBatchBlocking(it.map { pr -> pr.toEntity(p, gen) }) }
        return list.size
    }

    private fun Programme.toEntity(p: String, gen: Int) = ProgrammeEntity(
        providerId = p, gen = gen, epgId = epgId, startEpoch = startEpoch, stopEpoch = stopEpoch,
        title = title, description = description, category = category, episode = episode, icon = icon,
    )

    /**
     * Channels whose epg id is missing or does not exist in the guide get matched by name.
     * "Sky Sports Main Event HD" and "UK: SKY SPORTS MAIN EVENT FHD" both normalise to "skysportsmainevent".
     */
    private suspend fun resolveEpgIds(p: String) {
        val epgChannels = db.epgChannels().all(p)
        if (epgChannels.isEmpty()) return
        val ids = epgChannels.map { it.id }.toHashSet()
        val byName = HashMap<String, String>()
        epgChannels.forEach { c -> byName.putIfAbsent(c.normName, c.id) }
        val channels = db.channels().allOnce(p)
        var fixed = 0
        for (c in channels) {
            if (c.epgId != null && c.epgId in ids) continue
            val guess = byName[normalise(c.name)] ?: continue
            db.channels().setEpgId(p, c.id, guess)
            fixed++
        }
        TLog.d { "epg name matching fixed $fixed of ${channels.size} channels" }
    }

    // ---- Queries -----------------------------------------------------------------------------

    suspend fun programmes(p: String, epgId: String, from: Long, to: Long): List<ProgrammeEntity> =
        db.programmes().window(p, epgGen(p), epgId, from, to)

    /**
     * One channel's guide from the panel's own table (get_simple_data_table), for providers that
     * refuse xmltv.php. Stored under the current generation like XMLTV rows.
     */
    suspend fun fetchChannelEpg(record: ProviderRecord, channel: ChannelEntity): Int = withContext(Dispatchers.IO) {
        if (record.kind != ProviderKind.XTREAM) return@withContext 0
        val list = XtreamApi(AndroidHttp.core, record.xtream()).fullEpg(channel.id)
        val gen = epgGen(record.id)
        val key = channelEpgKey(channel)
        db.programmes().deleteFor(record.id, gen, key)
        db.programmes().insertBatchBlocking(list.map {
            ProgrammeEntity(providerId = record.id, gen = gen, epgId = key, startEpoch = it.startEpoch, stopEpoch = it.stopEpoch, title = it.title, description = it.description, category = null, episode = null, icon = null)
        })
        list.size
    }

    suspend fun programmeCount(p: String): Int = db.programmes().count(p, epgGen(p))

    /** Everything on between two times, across every channel, in one indexed read. */
    suspend fun programmesBetween(p: String, from: Long, to: Long): List<ProgrammeEntity> {
        val gen = epgGen(p)
        return withContext(Dispatchers.IO) { db.programmes().betweenBlocking(p, gen, from, to) }
    }

    /** The guide store's read: blocking, on the caller's IO thread, so it is never queued behind writes. */
    suspend fun programmesManyDirect(p: String, epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity> {
        if (epgIds.isEmpty()) return emptyList()
        val gen = epgGen(p)
        return withContext(Dispatchers.IO) {
            val dao = db.programmes()
            val out = ArrayList<ProgrammeEntity>(epgIds.size * 24)
            for (id in epgIds) out.addAll(dao.windowBlocking(p, gen, id, from, to))
            out
        }
    }

    /** Folds the write-ahead log back into the file. Called when the sweep goes quiet, so a busy stretch of writes cannot leave readers wading through a huge log. */
    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
    }
    fun walBytes(context: android.content.Context): Long = runCatching { java.io.File(context.getDatabasePath("sloopy.db").path + "-wal").length() }.getOrDefault(0L)

    suspend fun programmesMany(p: String, epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity> =
        if (epgIds.isEmpty()) emptyList() else db.programmes().windowMany(p, epgGen(p), epgIds, from, to)

    suspend fun nowPlaying(p: String, epgIds: List<String>, now: Long): Map<String, NowNext> {
        if (epgIds.isEmpty()) return emptyMap()
        val gen = epgGen(p)
        val out = HashMap<String, NowNext>()
        epgIds.distinct().chunked(500).forEach { chunk ->
            db.programmes().nowPlaying(p, gen, chunk, now).forEach { out[it.epgId] = it }
        }
        return out
    }

    suspend fun nowAndNext(p: String, epgId: String, now: Long, limit: Int = 3): List<ProgrammeEntity> =
        db.programmes().fromNow(p, epgGen(p), listOf(epgId), now).take(limit)

    suspend fun searchProgrammes(p: String, q: String, now: Long) = db.programmes().search(p, epgGen(p), q, now)

    suspend fun guideRange(p: String): Pair<Long, Long>? {
        val gen = epgGen(p)
        val a = db.programmes().earliest(p, gen) ?: return null
        val b = db.programmes().latest(p, gen) ?: return null
        return a to b
    }

    // ---- Stream URLs -------------------------------------------------------------------------

    fun liveUrl(record: ProviderRecord, channel: ChannelEntity): String = when {
        channel.directUrl != null -> channel.directUrl
        record.kind == ProviderKind.XTREAM -> record.xtream().liveUrl(channel.id, record.container)
        else -> throw IOException("No stream for this channel")
    }

    fun catchupUrl(record: ProviderRecord, channel: ChannelEntity, startEpoch: Long, durationMin: Int, zone: ZoneId): String? =
        if (record.kind == ProviderKind.XTREAM && channel.archive) record.xtream().timeshiftUrl(channel.id, startEpoch, durationMin, zone) else null

    fun vodUrl(record: ProviderRecord, id: Long, container: String): String = record.xtream().vodUrl(id, container)
    fun episodeUrl(record: ProviderRecord, id: Long, container: String): String = record.xtream().seriesUrl(id, container)

    companion object {
        private const val BATCH = 2000
        private val EPG_AGENT_NAMES = listOf("SloopyTV", "TiviMate", "VLC", "Chrome", "okhttp")
        /** Agents tried for the guide file, in order; the app's own first. */
        private val EPG_AGENTS = listOf(
            AndroidHttp.USER_AGENT,
            "TiviMate/5.1.6 (Android 11)",
            "VLC/3.0.20 LibVLC/3.0.20",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
            "okhttp/4.12.0",
        )
        const val EPG_MIN_INTERVAL_MS = 6L * 3600 * 1000

        private val junk = Regex("""\b(uk|us|usa|ca|de|fr|es|it|nl|au|ie)\b|\b(hd|fhd|uhd|4k|sd|hevc|h265|raw|backup|vip)\b|[^a-z0-9]""")
        fun normalise(name: String): String = junk.replace(name.lowercase(), "")
    }
}
