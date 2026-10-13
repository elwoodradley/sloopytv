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

    /** Channels in the selected group, locked groups hidden until unlocked for this session. */
    val visibleChannels: StateFlow<List<ChannelEntity>> =
        combine(channels, _group, favouriteIds, lockedGroups, unlockedSession) { all, g, favs, locked, unlocked ->
            val base = when (g) {
                Groups.ALL -> all
                Groups.FAV -> all.filter { it.id in favs }
                else -> all.filter { it.categoryId == g }
            }
            if (unlocked) base else base.filter { it.categoryId !in locked }
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
        val info = (s as? tv.sloopy.update.UpdateState.Available)?.info ?: (s as? tv.sloopy.update.UpdateState.NeedsPermission)?.info ?: return@launch
        updater.install(info)
    }

    val introEnabled: StateFlow<Boolean> = prefs.introEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
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

    private suspend fun ensureFresh(p: ProviderRecord) {
        val count = graph.db.channels().count(p.id)
        val age = System.currentTimeMillis() - prefs.refreshedAt(p.id, "channels")
        if (count == 0 || age > 24L * 3600 * 1000) refresh(p) else viewModelScope.launch {
            runCatching { catalog.refreshEpg(p) }.onFailure { TLog.w { "epg refresh: ${TLog.userMessage(it)}" } }
        }
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

    fun tune(c: ChannelEntity) {
        val p = active.value ?: return
        _playing.value = Playing.Live(c)
        viewModelScope.launch { prefs.setLastChannel(p.id, c.id) }
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
