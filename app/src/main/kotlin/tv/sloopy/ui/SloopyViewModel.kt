package tv.sloopy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.sloopy.TLog
import tv.sloopy.SloopyApp
import tv.sloopy.data.Catalog
import tv.sloopy.data.Demo
import tv.sloopy.data.ProviderKind
import tv.sloopy.data.ProviderRecord
import tv.sloopy.data.ProviderStore
import tv.sloopy.data.Vault
import tv.sloopy.data.db.CategoryEntity
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.FavouriteEntity
import tv.sloopy.data.db.NowNext
import tv.sloopy.player.PlayerHolder
import java.time.ZoneId

/** Pseudo groups shown above the provider's categories. */
const val GUIDE_HOURS_BACK = 2L
const val GUIDE_HOURS_FWD = 24L

object Groups {
    const val ALL = "\u0000all"
    const val FAV = "\u0000fav"
}

sealed class Playing {
    data class Live(val channel: ChannelEntity) : Playing()
    data class Catchup(val channel: ChannelEntity, val title: String, val startEpoch: Long, val stopEpoch: Long) : Playing()
    data class Vod(val id: Long, val title: String, val subtitle: String?, val image: String?, val container: String, val kind: String, val parentId: Long?) : Playing()
}

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class SloopyViewModel(app: Application) : AndroidViewModel(app) {
    val graph = SloopyApp.graph(app)
    val catalog: Catalog get() = graph.catalog
    val prefs get() = graph.prefs
    val providers: ProviderStore get() = graph.providers
    val player = PlayerHolder(app)

    private val unlockedSessionState = MutableStateFlow(false)
    val unlockedSession: StateFlow<Boolean> = unlockedSessionState

    val providerList: StateFlow<List<ProviderRecord>> = providers.providers

    val activeId: StateFlow<String?> = prefs.activeProviderId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val active: StateFlow<ProviderRecord?> = combine(providerList, activeId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val channels: StateFlow<List<ChannelEntity>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else graph.db.channels().all(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val rawLiveCategories: StateFlow<List<CategoryEntity>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else graph.db.categories().byKind(p.id, "live")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val groupOrder: StateFlow<List<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else prefs.groupOrder(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Provider's groups in the viewer's order; groups the order does not mention keep the provider's order after it. */
    val liveCategories: StateFlow<List<CategoryEntity>> = combine(rawLiveCategories, groupOrder) { cats, order ->
        val rank = order.withIndex().associate { (i, id) -> id to i }
        cats.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.sortKey }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun moveGroup(id: String, delta: Int) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val ids = liveCategories.value.map { it.id }.toMutableList()
        val i = ids.indexOf(id); val j = i + delta
        if (i < 0 || j < 0 || j >= ids.size) return@launch
        ids[i] = ids[j].also { ids[j] = ids[i] }
        prefs.setGroupOrder(p.id, ids)
    }

    val favouriteVodIds: StateFlow<Set<Long>> = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.favouriteItems().ids(p.id, "vod") }
        .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val favouriteSeriesIds: StateFlow<Set<Long>> = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.favouriteItems().ids(p.id, "series") }
        .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleItemFavourite(kind: String, id: Long) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val set = if (kind == "vod") favouriteVodIds.value else favouriteSeriesIds.value
        if (id in set) graph.db.favouriteItems().remove(p.id, kind, id)
        else graph.db.favouriteItems().add(tv.sloopy.data.db.FavouriteItemEntity(p.id, kind, id, System.currentTimeMillis()))
    }

    val favouriteIds: StateFlow<Set<Long>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else graph.db.favourites().ids(p.id)
    }.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val lockedGroups: StateFlow<Set<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet()) else prefs.lockedGroups(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _group = MutableStateFlow(Groups.ALL)
    val group: StateFlow<String> = _group

    /** "provider" | "number" | "name" for the selected group. */
    val channelSort: StateFlow<String> = combine(active, _group) { p, g -> p?.id to g }
        .flatMapLatest { (pid, g) -> if (pid == null) flowOf("provider") else prefs.channelSort(pid, g) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "provider")
    fun setChannelSort(mode: String) = viewModelScope.launch { active.value?.let { prefs.setChannelSort(it.id, _group.value, mode) } }
    /** "az" sorts the groups by name; "provider" restores the provider's order. */
    fun sortGroups(mode: String) = viewModelScope.launch {
        val p = active.value ?: return@launch
        prefs.setGroupOrder(p.id, if (mode == "az") rawLiveCategories.value.sortedBy { it.name.lowercase() }.map { it.id } else emptyList())
    }

    /** Channels in the selected group, locked groups hidden until unlocked for this session. */
    private val visibleBase: StateFlow<List<ChannelEntity>> =
        combine(channels, _group, favouriteIds, lockedGroups, unlockedSession) { all, g, favs, locked, unlocked ->
            val base = when (g) {
                Groups.ALL -> all
                Groups.FAV -> all.filter { it.id in favs }
                else -> all.filter { it.categoryId == g }
            }
            if (unlocked) base else base.filter { it.categoryId !in locked }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(visibleBase, channelSort) { list, mode ->
        when (mode) { "number" -> list.sortedBy { it.number }; "name" -> list.sortedBy { it.name.lowercase() }; else -> list }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing

    val currentChannel: StateFlow<ChannelEntity?> = _playing.map { (it as? Playing.Live)?.channel ?: (it as? Playing.Catchup)?.channel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _nowPlaying = MutableStateFlow<Map<String, NowNext>>(emptyMap())
    /** epgId -> what is on now, for whatever the channel list is showing. Refreshed each minute. */
    val nowPlaying: StateFlow<Map<String, NowNext>> = _nowPlaying

    /** Long-press Back from anywhere: close everything and return to the picture. */
    val homeEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun goHome() { homeEvents.tryEmit(Unit) }
    /** Set before navigating to the guide so it opens with the sections panel (the "menu") out. */
    @Volatile var guideOpensAtSections = false
    /** Which rail row Movies and Series were last on, so Back and forth lands in the same place. */
    val librarySelection = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var settingsSection = "providers"
    /** The last search text, so Back from a result lands on the same results. */
    @Volatile var lastSearch = ""
    fun takeGuideOpensAtSections(): Boolean = guideOpensAtSections.also { guideOpensAtSections = false }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    val refreshState = catalog.refresh
    val updater get() = graph.updater
    val updateState: StateFlow<tv.sloopy.update.UpdateState> = graph.updater.state
    private var announcedUpdate: String? = null

    fun checkForUpdate(force: Boolean = false) = viewModelScope.launch {
        val r = updater.check(force)
        if (r is tv.sloopy.update.UpdateState.Available && announcedUpdate != r.info.version) {
            announcedUpdate = r.info.version
            _toast.value = "SloopyTV ${r.info.version} is ready. Settings › About to install."
        }
    }
    fun installUpdate() = viewModelScope.launch {
        val s = updateState.value
        if (s is tv.sloopy.update.UpdateState.Ready && updater.relaunch()) return@launch
        val info = (s as? tv.sloopy.update.UpdateState.Available)?.info ?: (s as? tv.sloopy.update.UpdateState.NeedsPermission)?.info
            ?: (s as? tv.sloopy.update.UpdateState.Ready)?.info ?: return@launch
        updater.install(info)
    }

    // ---- Paywall --------------------------------------------------------------------------------
    private val _payConfig = MutableStateFlow<tv.sloopy.pay.PayConfig?>(null)
    val payConfig: StateFlow<tv.sloopy.pay.PayConfig?> = _payConfig
    private val license: StateFlow<String?> = prefs.license.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** True while the operator has a product set and this stick has no key for it. */
    val locked: StateFlow<Boolean> = combine(_payConfig, license) { c, k -> c?.enabled == true && k.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    suspend fun unlock(key: String) {
        val c = _payConfig.value ?: throw IllegalStateException("Could not read the unlock settings. Check the connection and try again.")
        when (val v = tv.sloopy.pay.License.verify(c.gumroadProduct, key)) {
            is tv.sloopy.pay.Verdict.Valid -> { prefs.setLicense(key); prefs.setLicenseCheckedAt(System.currentTimeMillis()); _toast.value = "Unlocked. Stay tuned." }
            is tv.sloopy.pay.Verdict.Invalid -> throw IllegalStateException(v.why)
            tv.sloopy.pay.Verdict.Unknown -> throw IllegalStateException("Could not reach the license server. Check the connection and try again.")
        }
    }
    init {
        viewModelScope.launch {
            codeServer.collect { server ->
                _payConfig.value = tv.sloopy.pay.License.config(server) ?: tv.sloopy.pay.PayConfig("", "", "")
                // A week after the last check, confirm the key is still good; refunds revoke it, no network keeps it.
                val c = _payConfig.value ?: return@collect
                val k = license.value
                if (c.enabled && !k.isNullOrBlank() && System.currentTimeMillis() - prefs.licenseCheckedAt.first() > 7L * 86_400_000) {
                    when (tv.sloopy.pay.License.verify(c.gumroadProduct, k)) {
                        is tv.sloopy.pay.Verdict.Valid -> prefs.setLicenseCheckedAt(System.currentTimeMillis())
                        is tv.sloopy.pay.Verdict.Invalid -> prefs.setLicense(null)
                        tv.sloopy.pay.Verdict.Unknown -> {}
                    }
                }
            }
        }
    }

    // ---- Recordings ------------------------------------------------------------------------------
    val recordings: StateFlow<List<tv.sloopy.data.db.RecordingEntity>> = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.recordings().all(p.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recordFolder: StateFlow<String?> = prefs.recordFolder.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setRecordFolder(uri: String?) = viewModelScope.launch { prefs.setRecordFolder(uri) }
    init { viewModelScope.launch { graph.db.recordings().finishStale() } }

    /** Records [channel] from [startEpoch] to [stopEpoch]; starts at once when the start has passed. */
    /** Starts a minute early and runs [padAfterSec] past the end, so late-running shows are not cut. */
    fun scheduleRecording(channel: ChannelEntity, title: String, startEpoch: Long, stopEpoch: Long, padAfterSec: Long = 120) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val now = System.currentTimeMillis() / 1000
        if (stopEpoch <= now) { _toast.value = "That programme has already ended"; return@launch }
        val start = maxOf(startEpoch - 60, now)
        val id = graph.db.recordings().insert(tv.sloopy.data.db.RecordingEntity(providerId = p.id, channelId = channel.id, channelName = channel.name, title = title, startEpoch = start, stopEpoch = stopEpoch + padAfterSec, status = "scheduled"))
        val ctx = getApplication<android.app.Application>()
        if (start * 1000 - tv.sloopy.record.Recorder.LEAD_MS <= System.currentTimeMillis()) tv.sloopy.record.Recorder.start(ctx, id)
        else tv.sloopy.record.Recorder.schedule(ctx, id, start)
        _toast.value = if (startEpoch <= now) "Recording $title" else "Will record $title at ${tv.sloopy.ui.common.TimeFmt.hhmm(startEpoch)}"
    }
    /** Records what is on [channel] now until it ends, or for two hours when the guide has nothing. */
    fun recordNow(channel: ChannelEntity) = viewModelScope.launch {
        val now = System.currentTimeMillis() / 1000
        val prog = programmesFor(channel, guideWindowStart(now)).firstOrNull { it.startEpoch <= now && it.stopEpoch > now }
        scheduleRecording(channel, prog?.title ?: channel.name, now, prog?.stopEpoch ?: (now + 7200))
    }
    fun stopRecording(r: tv.sloopy.data.db.RecordingEntity) { tv.sloopy.record.Recorder.stop(getApplication(), r.id) }
    fun deleteRecording(r: tv.sloopy.data.db.RecordingEntity) = viewModelScope.launch {
        val ctx = getApplication<android.app.Application>()
        if (r.status == "scheduled") tv.sloopy.record.Recorder.unschedule(ctx, r.id)
        if (r.status == "recording") tv.sloopy.record.Recorder.stop(ctx, r.id)
        tv.sloopy.record.Recorder.delete(ctx, r.uri)
        graph.db.recordings().delete(r.id)
        graph.db.progress().remove(r.providerId, "recording", r.id)
    }
    fun playRecording(r: tv.sloopy.data.db.RecordingEntity) = viewModelScope.launch {
        val uri = r.uri ?: return@launch
        val resume = graph.db.progress().get(r.providerId, "recording", r.id)?.positionMs ?: 0L
        _playing.value = Playing.Vod(r.id, r.title, r.channelName + " · " + tv.sloopy.ui.common.TimeFmt.dateTime(r.startEpoch * 1000), null, "ts", "recording", null)
        player.play(uri, isLive = false, startPositionMs = resume)
    }

    // ---- Transfer / sync -----------------------------------------------------------------------
    private val syncManager = tv.sloopy.sync.SyncManager(graph.db, prefs, providers)
    val syncCode: StateFlow<String?> = prefs.syncCode.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val syncedAt: StateFlow<Long> = prefs.syncedAt.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus
    private var syncing = false
    private var lastUploadAt = 0L

    /** Uploads this stick's bundle under its code. Quiet unless [announce]; automatic uploads wait a minute between runs. */
    fun syncNow(announce: Boolean = false) = viewModelScope.launch {
        val code = syncCode.value ?: return@launch
        if (syncing) return@launch
        syncing = true
        try {
            if (!announce) { val wait = 60_000 - (System.currentTimeMillis() - lastUploadAt); if (wait > 0) delay(wait) }
            lastUploadAt = System.currentTimeMillis()
            val bundle = syncManager.build()
            val at = tv.sloopy.sync.SyncClient.upload(codeServer.value, code, syncManager.encode(bundle))
            prefs.setSyncedAt(at)
            if (announce) _syncStatus.value = "Uploaded: ${bundle.summary}."
        } catch (e: Exception) {
            _syncStatus.value = "Upload failed: ${TLog.userMessage(e)}"
            TLog.w { "sync upload: ${TLog.userMessage(e)}" }
        } finally { syncing = false }
    }
    fun enableSync() = viewModelScope.launch { prefs.setSyncCode(tv.sloopy.sync.SyncClient.newCode()); _syncStatus.value = null; syncNow(announce = true) }
    fun disableSync() = viewModelScope.launch { prefs.setSyncCode(null); prefs.setSyncedAt(0); _syncStatus.value = "Sync is off. The last backup stays on the server under the old code until it is replaced." }
    /** Pulls the bundle behind [code] onto this stick and joins that code. */
    suspend fun restoreFromCode(code: String): String {
        val bytes = tv.sloopy.sync.SyncClient.download(codeServer.value, code)
        val bundle = syncManager.decode(bytes)
        syncManager.apply(bundle)
        prefs.setSyncCode(tv.sloopy.sync.SyncClient.normalise(code))
        prefs.setSyncedAt(bundle.at)
        active.value?.let { refresh(it) }
        return bundle.summary
    }
    init {
        // Re-upload a few seconds after anything in the bundle changes.
        viewModelScope.launch {
            val progressTick = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.progress().recent(p.id, 1) }
            kotlinx.coroutines.flow.merge(
                favouriteIds, favouriteVodIds, favouriteSeriesIds, groupOrder, lockedGroups, providerList, progressTick,
                prefs.theme, prefs.clock12, prefs.frameRateMatch, prefs.introEnabled, prefs.osdSeconds, prefs.searchHistory, prefs.license,
            ).map { }.drop(1).debounce(8_000).collect { if (syncCode.value != null) syncNow() }
        }
    }

    val introEnabled: StateFlow<Boolean> = prefs.introEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val clock12: StateFlow<Boolean> = prefs.clock12.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val theme: StateFlow<String> = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "phosphor")
    val frameRateMatch: StateFlow<Boolean> = prefs.frameRateMatch.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    init {
        viewModelScope.launch { clock12.collect { tv.sloopy.ui.common.TimeFmt.twelveHour = it } }
        viewModelScope.launch { theme.collect { tv.sloopy.ui.theme.Sloopy.palette = tv.sloopy.ui.theme.Palettes.byId(it) } }
    }
    val osdSeconds: StateFlow<Int> = prefs.osdSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, 4)

    /** Settings override, else the build's default. Empty means the feature is hidden. */
    val codeServer: StateFlow<String> = prefs.codeServer.map { it?.takeIf { s -> s.isNotBlank() } ?: tv.sloopy.BuildConfig.CODE_SERVER }
        .stateIn(viewModelScope, SharingStarted.Eagerly, tv.sloopy.BuildConfig.CODE_SERVER)

    val hasPin: StateFlow<Boolean> = prefs.pinHash.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var nowJob: Job? = null

    init {
        viewModelScope.launch {
            active.collect { p ->
                if (p != null) {
                    _group.value = prefs.lastGroup(p.id) ?: Groups.ALL
                    ensureFresh(p)
                }
            }
        }
        viewModelScope.launch {
            visibleChannels.collect { restartNowPlaying() }
        }
        viewModelScope.launch {
            // Quiet check a little after launch, then every six hours while the app is open.
            delay(15_000)
            while (true) { checkForUpdate(); delay(6 * 3600_000L) }
        }
    }

    // ---- Providers ---------------------------------------------------------------------------

    fun setActive(id: String) = viewModelScope.launch {
        prefs.setActiveProvider(id)
        _playing.value = null
        player.stop()
    }

    fun addDemo() = viewModelScope.launch {
        providers.upsert(Demo.record)
        prefs.setActiveProvider(Demo.PROVIDER_ID)
    }

    suspend fun saveProvider(record: ProviderRecord, makeActive: Boolean = true) {
        providers.upsert(record)
        if (makeActive) prefs.setActiveProvider(record.id)
    }

    fun removeProvider(id: String) = viewModelScope.launch {
        providers.remove(id)
        if (activeId.value == id) prefs.setActiveProvider(providerList.value.firstOrNull()?.id)
        graph.db.channels().deleteAll(id)
        graph.db.programmes().deleteAll(id)
        graph.db.vod().deleteAll(id)
        graph.db.series().deleteAll(id)
        graph.db.meta().deleteAll(id)
    }

    /** Why the last guide download failed, shown on the guide so a viewer can report it. */
    private val _epgError = MutableStateFlow<String?>(null)
    val epgError: StateFlow<String?> = _epgError
    fun refreshEpgNow() = viewModelScope.launch {
        val p = active.value ?: return@launch
        runCatching { catalog.refreshEpg(p) }
            .onSuccess { _epgError.value = null }
            .onFailure { _epgError.value = TLog.userMessage(it); TLog.w { "epg refresh: ${TLog.userMessage(it)}" } }
    }

    private suspend fun ensureFresh(p: ProviderRecord) {
        val count = graph.db.channels().count(p.id)
        val age = System.currentTimeMillis() - prefs.refreshedAt(p.id, "channels")
        if (count == 0 || age > 24L * 3600 * 1000) refresh(p) else refreshEpgNow()
    }

    fun refresh(p: ProviderRecord? = active.value, force: Boolean = false) = viewModelScope.launch {
        p ?: return@launch
        runCatching { catalog.refreshAll(p, includeEpg = false) }
            .onFailure { _toast.value = TLog.userMessage(it) }
        runCatching { catalog.refreshEpg(p, force) }
            .onFailure { _toast.value = "Guide: " + TLog.userMessage(it) }
    }

    // ---- Groups and channels ----------------------------------------------------------------

    fun selectGroup(g: String) {
        _group.value = g
        viewModelScope.launch { active.value?.let { prefs.setLastGroup(it.id, g) } }
    }

    fun toggleFavourite(c: ChannelEntity) = viewModelScope.launch {
        val p = active.value ?: return@launch
        if (c.id in favouriteIds.value) graph.db.favourites().remove(p.id, c.id)
        else graph.db.favourites().add(FavouriteEntity(p.id, c.id, System.currentTimeMillis()))
    }

    private fun restartNowPlaying() {
        nowJob?.cancel()
        nowJob = viewModelScope.launch {
            while (true) {
                val p = active.value
                val ids = visibleChannels.value.mapNotNull { it.epgId }
                if (p != null && ids.isNotEmpty()) {
                    runCatching { _nowPlaying.value = catalog.nowPlaying(p.id, ids, System.currentTimeMillis() / 1000) }
                }
                delay(60_000)
            }
        }
    }

    // ---- Playback ---------------------------------------------------------------------------

    /** The ten channels watched most recently, newest first, for the OK overlay. */
    val recentChannels: StateFlow<List<ChannelEntity>> = combine(channels, active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else prefs.recentChannels(p.id) }) { all, ids ->
        val byId = all.associateBy { it.id }
        ids.mapNotNull { byId[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The episodes around the one playing, so the player can offer the next one. */
    var episodeQueue: List<tv.sloopy.core.model.Episode> = emptyList()
        private set
    var episodeQueueSeries: Pair<Long, String>? = null   // series id, series title
        private set
    var episodeQueueImage: String? = null
        private set

    fun setEpisodeQueue(seriesId: Long, seriesTitle: String, image: String?, episodes: List<tv.sloopy.core.model.Episode>) {
        episodeQueue = episodes; episodeQueueSeries = seriesId to seriesTitle; episodeQueueImage = image
    }

    fun nextEpisodeAfter(episodeId: Long): tv.sloopy.core.model.Episode? {
        val i = episodeQueue.indexOfFirst { it.id == episodeId }
        return if (i >= 0 && i + 1 < episodeQueue.size) episodeQueue[i + 1] else null
    }

    // ---- Guide programme cache ---------------------------------------------------------------
    // Keyed by window start and epgId. Warmed around the current channel while the picture is on,
    // so the grid is full on its first frame after Back. Cleared when listings are refreshed.
    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, List<tv.sloopy.data.db.ProgrammeEntity>>()
    private fun epgKey(epgId: String, winStart: Long) = "$winStart|$epgId"
    fun guideWindowStart(now: Long = System.currentTimeMillis() / 1000): Long = (now / 1800) * 1800 - GUIDE_HOURS_BACK * 3600
    fun cachedProgrammes(epgId: String, winStart: Long): List<tv.sloopy.data.db.ProgrammeEntity>? = epgCache[epgKey(epgId, winStart)]
    private val channelEpgFetched = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val channelEpgGate = kotlinx.coroutines.sync.Semaphore(6)
    /**
     * Guide rows for one channel: cache, then the database, then (Xtream only, at most hourly per
     * channel) the panel's own per-channel table for providers whose xmltv.php is refused.
     */
    suspend fun programmesFor(channel: ChannelEntity, winStart: Long): List<tv.sloopy.data.db.ProgrammeEntity> {
        val key = tv.sloopy.data.channelEpgKey(channel)
        cachedProgrammes(key, winStart)?.takeIf { it.isNotEmpty() }?.let { return it }
        val p = active.value ?: return emptyList()
        val winEnd = winStart + (GUIDE_HOURS_BACK + GUIDE_HOURS_FWD) * 3600
        var rows = runCatching { catalog.programmes(p.id, key, winStart, winEnd) }.getOrDefault(emptyList())
        if (rows.isEmpty() && p.kind == tv.sloopy.data.ProviderKind.XTREAM) {
            val now = System.currentTimeMillis()
            if (now - (channelEpgFetched[channel.id] ?: 0L) > 3600_000L) {
                channelEpgFetched[channel.id] = now
                try {
                    channelEpgGate.withPermit { catalog.fetchChannelEpg(p, channel) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    channelEpgFetched.remove(channel.id); throw e            // row scrolled away: try again next time
                } catch (e: Exception) {
                    channelEpgFetched[channel.id] = now - 3600_000L + 600_000L   // refused: wait ten minutes before asking again
                    TLog.w { "channel epg: ${TLog.userMessage(e)}" }
                }
                rows = runCatching { catalog.programmes(p.id, key, winStart, winEnd) }.getOrDefault(emptyList())
            }
        }
        epgCache[epgKey(key, winStart)] = rows
        return rows
    }
    fun prefetchGuide() = viewModelScope.launch {
        val p = active.value ?: return@launch
        val list = visibleChannels.value
        if (list.isEmpty()) return@launch
        if (epgCache.size > 4000) epgCache.clear()
        val i = list.indexOfFirst { it.id == currentChannel.value?.id }.coerceAtLeast(0)
        val ws = guideWindowStart()
        // The top of the group (what a fresh grid shows) plus the rows around the current channel.
        val ids = (list.take(60) + list.subList((i - 20).coerceAtLeast(0), (i + 25).coerceAtMost(list.size)))
            .map { tv.sloopy.data.channelEpgKey(it) }.distinct().filter { cachedProgrammes(it, ws) == null }
        if (ids.isEmpty()) return@launch
        val rows = runCatching { catalog.programmesMany(p.id, ids, ws, ws + (GUIDE_HOURS_BACK + GUIDE_HOURS_FWD) * 3600) }.getOrDefault(emptyList()).groupBy { it.epgId }
        ids.forEach { epgCache[epgKey(it, ws)] = rows[it] ?: emptyList() }
    }
    init {
        viewModelScope.launch { combine(currentChannel, visibleChannels) { c, l -> c?.id to l.size }.collect { prefetchGuide() } }
        viewModelScope.launch { catalog.refresh.collect { if (it is tv.sloopy.data.RefreshState.Done) { epgCache.clear(); prefetchGuide() } } }
    }

    fun forgetProgress(r: tv.sloopy.data.db.ProgressEntity) = viewModelScope.launch { graph.db.progress().remove(r.providerId, r.kind, r.itemId) }

    fun tune(c: ChannelEntity) {
        val p = active.value ?: return
        _playing.value = Playing.Live(c)
        viewModelScope.launch { prefs.setLastChannel(p.id, c.id); prefs.pushRecent(p.id, c.id) }
        try {
            player.play(catalog.liveUrl(p, c), isLive = true)
        } catch (e: Exception) {
            _toast.value = TLog.userMessage(e)
        }
    }

    fun tuneNumber(n: Int): Boolean {
        val c = channels.value.firstOrNull { it.number == n } ?: return false
        tune(c)
        return true
    }

    /** Up/down zapping follows the current group's order. */
    fun step(delta: Int) {
        val list = visibleChannels.value.ifEmpty { channels.value }
        if (list.isEmpty()) return
        val cur = currentChannel.value
        val i = list.indexOfFirst { it.id == cur?.id }
        val next = if (i < 0) 0 else ((i + delta) % list.size + list.size) % list.size
        tune(list[next])
    }

    fun playCatchup(c: ChannelEntity, title: String, startEpoch: Long, stopEpoch: Long) {
        val p = active.value ?: return
        val url = catalog.catchupUrl(p, c, startEpoch, ((stopEpoch - startEpoch) / 60).toInt().coerceAtLeast(1), ZoneId.systemDefault())
        if (url == null) { _toast.value = "No catch-up for this channel"; return }
        _playing.value = Playing.Catchup(c, title, startEpoch, stopEpoch)
        player.play(url, isLive = false)
    }

    fun playVod(id: Long, title: String, subtitle: String?, image: String?, container: String, kind: String, parentId: Long?, resumeMs: Long) {
        val p = active.value ?: return
        if (kind == "recording") { viewModelScope.launch { graph.db.recordings().get(id)?.let { playRecording(it) } }; return }
        val url = if (kind == "episode") catalog.episodeUrl(p, id, container) else catalog.vodUrl(p, id, container)
        _playing.value = Playing.Vod(id, title, subtitle, image, container, kind, parentId)
        player.play(url, isLive = false, startPositionMs = resumeMs)
    }

    /** Called when leaving the VOD player to remember position. */
    fun saveProgress() = viewModelScope.launch {
        val p = active.value ?: return@launch
        val v = _playing.value as? Playing.Vod ?: return@launch
        val pos = player.position()
        val dur = player.duration()
        if (dur <= 0) return@launch
        if (pos > dur - 60_000 || pos < 15_000) {
            if (pos > dur - 60_000) graph.db.progress().remove(p.id, v.kind, v.id)
            return@launch
        }
        graph.db.progress().upsert(
            tv.sloopy.data.db.ProgressEntity(p.id, v.kind, v.id, v.parentId, pos, dur, System.currentTimeMillis(), v.title, v.subtitle, v.image, v.container)
        )
    }

    fun resumeLastChannel() {
        if (_playing.value != null) return
        viewModelScope.launch {
            val p = active.value ?: return@launch
            val lastId = prefs.lastChannel(p.id)
            val list = channels.value.ifEmpty { graph.db.channels().allOnce(p.id) }
            val c = list.firstOrNull { it.id == lastId } ?: visibleChannels.value.firstOrNull() ?: list.firstOrNull() ?: return@launch
            tune(c)
        }
    }

    fun stopPlayback() {
        _playing.value = null
        player.stop()
    }

    fun toast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    // ---- Parental ---------------------------------------------------------------------------

    suspend fun checkPin(pin: String): Boolean {
        val salt = prefs.pinSalt.first() ?: return true
        val hash = prefs.pinHash.first() ?: return true
        val ok = Vault.hashPin(pin, hex(salt)).toHex() == hash
        if (ok) unlockedSessionState.value = true
        return ok
    }

    suspend fun setPin(pin: String?) {
        if (pin == null) { prefs.setPin(null, null); unlockedSessionState.value = false; return }
        val salt = Vault.newSalt()
        prefs.setPin(salt.toHex(), Vault.hashPin(pin, salt).toHex())
    }

    fun setLocked(groupId: String, locked: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cur = lockedGroups.value.toMutableSet()
        if (locked) cur.add(groupId) else cur.remove(groupId)
        prefs.setLockedGroups(p.id, cur)
    }

    fun relock() { unlockedSessionState.value = false }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
