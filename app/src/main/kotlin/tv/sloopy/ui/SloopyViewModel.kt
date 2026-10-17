package tv.sloopy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.contentOrNull
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
const val GUIDE_HOURS_BACK = 48L
const val GUIDE_HOURS_FWD = 24L
/** Hours of listings held in memory behind and ahead of the guide's view. */
const val GUIDE_BAND_BACK = 6L
const val GUIDE_BAND_FWD = 6L

const val VLC = "org.videolan.vlc"

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
    @kotlinx.serialization.Serializable
    data class Profile(val id: String, val name: String, val groups: List<String> = emptyList(), val vodCats: List<String> = emptyList(), val seriesCats: List<String> = emptyList())
    private val profileJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val profiles: StateFlow<List<Profile>> = prefs.profiles.map { runCatching { profileJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), it) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeProfile: StateFlow<Profile?> = combine(profiles, prefs.activeProfile) { list, id -> list.firstOrNull { it.id == id } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val allLiveCategories: StateFlow<List<CategoryEntity>> = combine(rawLiveCategories, groupOrder) { cats, order ->
        val rank = order.withIndex().associate { (i, id) -> id to i }
        cats.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.sortKey }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** The groups this profile may see (all of them when no profile is active). */
    val liveCategories: StateFlow<List<CategoryEntity>> = combine(allLiveCategories, prefs.activeProfile, prefs.profiles) { cats, id, json ->
        val pr = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), json) }.getOrDefault(emptyList()).firstOrNull { it.id == id }
        if (pr == null) cats else cats.filter { it.id in pr.groups }
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
    /** Groups the viewer hid from the guide, channel list and search. */
    val hiddenGroups: StateFlow<Set<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet()) else prefs.hiddenGroups(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val hiddenChannels: StateFlow<Set<Long>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet()) else prefs.hiddenChannels(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun setChannelHidden(id: Long, hidden: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cur = hiddenChannels.value.toMutableSet()
        if (hidden) cur.add(id) else cur.remove(id)
        prefs.setHiddenChannels(p.id, cur)
    }

    // ---- Reminders -----------------------------------------------------------------------------
    val reminders: StateFlow<List<tv.sloopy.data.db.ReminderEntity>> = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.reminders().all(p.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun hasReminder(channelId: Long, startEpoch: Long): Boolean = reminders.value.any { it.channelId == channelId && it.startEpoch == startEpoch }
    fun addReminder(c: ChannelEntity, title: String, startEpoch: Long, stopEpoch: Long) = viewModelScope.launch {
        val p = active.value ?: return@launch
        graph.db.reminders().deleteFor(p.id, c.id, startEpoch)
        graph.db.reminders().insert(tv.sloopy.data.db.ReminderEntity(providerId = p.id, channelId = c.id, channelName = c.name, title = title, startEpoch = startEpoch, stopEpoch = stopEpoch))
        _toast.value = "Reminder set: $title at ${tv.sloopy.ui.common.TimeFmt.hhmm(startEpoch)}. SloopyTV has to be open."
    }
    fun removeReminder(c: ChannelEntity, startEpoch: Long) = viewModelScope.launch { active.value?.let { graph.db.reminders().deleteFor(it.id, c.id, startEpoch) } }
    /** The reminder whose programme is starting, shown as a prompt over whatever is on screen. */
    private val _dueReminder = MutableStateFlow<tv.sloopy.data.db.ReminderEntity?>(null)
    val dueReminder: StateFlow<tv.sloopy.data.db.ReminderEntity?> = _dueReminder
    fun dismissReminder(r: tv.sloopy.data.db.ReminderEntity, watch: Boolean) {
        viewModelScope.launch { graph.db.reminders().delete(r.id) }
        _dueReminder.value = null
        if (watch) channels.value.firstOrNull { it.id == r.channelId }?.let { tune(it); goHome() }
    }
    init {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis() / 1000
                runCatching { graph.db.reminders().purge(now); if (_dueReminder.value == null) _dueReminder.value = graph.db.reminders().due(now) }
                delay(15_000)
            }
        }
    }

    fun setHidden(groupId: String, hidden: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cur = hiddenGroups.value.toMutableSet()
        if (hidden) cur.add(groupId) else cur.remove(groupId)
        prefs.setHiddenGroups(p.id, cur)
        if (hidden && _group.value == groupId) selectGroup(Groups.ALL)
    }

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

    // ---- Duplicate channels ----------------------------------------------------------------------
    /** One cluster per real channel: every copy the provider carries, and the one shown for it. */
    data class Cluster(val key: String, val members: List<ChannelEntity>, val primary: ChannelEntity)
    data class Dupes(val byChannel: Map<Long, Cluster>, val possible: List<Pair<ChannelEntity, ChannelEntity>>)

    val collapseDupes: StateFlow<Boolean> = prefs.collapseDupes.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private fun <T> perProvider(f: (String) -> kotlinx.coroutines.flow.Flow<T>, empty: T): StateFlow<T> =
        active.flatMapLatest { p -> if (p == null) flowOf(empty) else f(p.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, empty)
    val dupSplit: StateFlow<Set<String>> = perProvider({ prefs.dupSplit(it) }, emptySet())
    val dupMerged: StateFlow<Set<String>> = perProvider({ prefs.dupMerged(it) }, emptySet())
    val dupDismissed: StateFlow<Set<String>> = perProvider({ prefs.dupDismissed(it) }, emptySet())
    val dupPreferred: StateFlow<Map<String, Long>> = perProvider({ prefs.dupPreferred(it) }, emptyMap())

    val dupes: StateFlow<Dupes> = combine(channels, dupSplit, dupMerged, dupDismissed, dupPreferred) { all, split, merged, dismissed, preferred ->
        // Strong signal: identical stripped names. Confirmed pairs join their partner's cluster.
        val keyOf = HashMap<Long, String>(all.size * 2)
        for (c in all) keyOf[c.id] = tv.sloopy.core.model.Dupes.key(c.name).ifEmpty { "#${c.id}" }
        for (pair in merged) {
            val (a, b) = pair.split(':').mapNotNull { it.toLongOrNull() }.let { if (it.size == 2) it[0] to it[1] else continue }
            val ka = keyOf[a] ?: continue; val kb = keyOf[b] ?: continue
            if (ka != kb) for ((id, k) in keyOf) if (k == kb) keyOf[id] = ka
        }
        val groups = all.groupBy { keyOf[it.id]!! }
        val byChannel = HashMap<Long, Cluster>(all.size * 2)
        for ((k, members) in groups) {
            if (members.size < 2 || k in split) { members.forEach { byChannel[it.id] = Cluster(k, listOf(it), it) }; continue }
            val primary = members.firstOrNull { it.id == preferred[k] }
                ?: members.maxWithOrNull(compareBy({ tv.sloopy.core.model.Dupes.quality(it.name) }, { -it.sortKey }))
                ?: members.first()
            val cl = Cluster(k, members, primary)
            members.forEach { byChannel[it.id] = cl }
        }
        // Weaker signal, offered rather than acted on: same guide id, different names.
        val possible = ArrayList<Pair<ChannelEntity, ChannelEntity>>()
        val byEpg = all.filter { it.epgId != null }.groupBy { it.epgId!! }
        for ((_, same) in byEpg) {
            if (same.size < 2) continue
            val reps = same.distinctBy { keyOf[it.id] }
            for (i in reps.indices) for (j in i + 1 until reps.size) {
                val a = reps[i]; val b = reps[j]
                val tag = "${minOf(a.id, b.id)}:${maxOf(a.id, b.id)}"
                if (tag !in dismissed && tag !in merged) possible.add(a to b)
            }
        }
        Dupes(byChannel, possible.take(60))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Dupes(emptyMap(), emptyList()))

    fun copiesOf(c: ChannelEntity): List<ChannelEntity> = dupes.value.byChannel[c.id]?.members ?: listOf(c)
    fun clusterOf(c: ChannelEntity): Cluster? = dupes.value.byChannel[c.id]?.takeIf { it.members.size > 1 }
    fun preferCopy(c: ChannelEntity) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cl = dupes.value.byChannel[c.id] ?: return@launch
        prefs.setDupPreferred(p.id, dupPreferred.value + (cl.key to c.id))
        if (currentChannel.value?.let { dupes.value.byChannel[it.id]?.key } == cl.key) tune(c)
    }
    fun splitCluster(c: ChannelEntity) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cl = dupes.value.byChannel[c.id] ?: return@launch
        prefs.setDupSplit(p.id, dupSplit.value + cl.key)
    }
    fun unsplitCluster(key: String) = viewModelScope.launch { active.value?.let { prefs.setDupSplit(it.id, dupSplit.value - key) } }
    fun mergePossible(a: ChannelEntity, b: ChannelEntity, same: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val tag = "${minOf(a.id, b.id)}:${maxOf(a.id, b.id)}"
        if (same) prefs.setDupMerged(p.id, dupMerged.value + tag) else prefs.setDupDismissed(p.id, dupDismissed.value + tag)
    }
    /** The list with every cluster reduced to the copy shown for it. */
    /**
     * One row per cluster, and the row shown is always a member of [list]: the preferred copy when
     * it is in the list, else the best copy that is. A cluster's primary from another group never
     * stands in for one here (a viewer found UK feeds appearing inside US groups that way).
     */
    fun collapse(list: List<ChannelEntity>): List<ChannelEntity> {
        if (!collapseDupes.value) return list
        val d = dupes.value.byChannel
        val here = list.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        return list.mapNotNull { c ->
            val cl = d[c.id]
            if (cl == null || cl.members.size < 2) c
            else if (seen.add(cl.key)) (if (cl.primary.id in here) cl.primary else cl.members.filter { it.id in here }.maxByOrNull { tv.sloopy.core.model.Dupes.quality(it.name) } ?: c)
            else null
        }
    }

    /** Channels in the selected group, locked groups hidden until unlocked for this session. */
    private val visibleBase: StateFlow<List<ChannelEntity>> =
        combine(channels, _group, favouriteIds, lockedGroups, unlockedSession, dupes) { args ->
            @Suppress("UNCHECKED_CAST")
            val all = args[0] as List<ChannelEntity>; val g = args[1] as String; val favs = args[2] as Set<Long>
            val locked = args[3] as Set<String>; val unlocked = args[4] as Boolean; val d = args[5] as Dupes
            // A favourite on any copy counts for the whole cluster.
            val favKeys = favs.mapNotNull { d.byChannel[it]?.key }.toSet()
            val base = when (g) {
                Groups.ALL -> all
                Groups.FAV -> all.filter { it.id in favs || (d.byChannel[it.id]?.key in favKeys) }
                else -> all.filter { it.categoryId == g }
            }
            if (unlocked) base else base.filter { it.categoryId !in locked }
        }.combine(activeProfile) { list, pr -> if (pr == null) list else list.filter { it.categoryId in pr.groups } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(visibleBase, channelSort, hiddenGroups, hiddenChannels, collapseDupes) { base, mode, hidden, hiddenCh, _ ->
        val list = collapse(base.filter { (hidden.isEmpty() || it.categoryId !in hidden) && it.id !in hiddenCh })
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
    /** True while a prompt covers the screen, so screens underneath do not pull focus to themselves. */
    @Volatile var modalUp = false
    /** The last search text, so Back from a result lands on the same results. */
    @Volatile var lastSearch = ""
    fun takeGuideOpensAtSections(): Boolean = guideOpensAtSections.also { guideOpensAtSections = false }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    val refreshState = catalog.refresh
    val updater get() = graph.updater
    val updateState: StateFlow<tv.sloopy.update.UpdateState> = graph.updater.state
    private var announcedUpdate: String? = null
    /** An update the viewer has not answered yet: the root shows it as a prompt with Install now. */
    private val _updatePrompt = MutableStateFlow<tv.sloopy.update.UpdateInfo?>(null)
    val updatePrompt: StateFlow<tv.sloopy.update.UpdateInfo?> = _updatePrompt
    fun dismissUpdatePrompt() { _updatePrompt.value = null }

    fun checkForUpdate(force: Boolean = false) = viewModelScope.launch {
        val r = updater.check(force)
        if (r is tv.sloopy.update.UpdateState.Available && announcedUpdate != r.info.version) {
            announcedUpdate = r.info.version
            _updatePrompt.value = r.info
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

    // ---- Operator: announcement, dead-channel reports ---------------------------------------------
    val operatorName: StateFlow<String> = _payConfig.map { it?.operatorName.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val reportsEnabled: StateFlow<Boolean> = _payConfig.map { it?.reportsEnabled == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** An announcement this stick has not shown yet, or null. */
    val announcement: StateFlow<tv.sloopy.pay.PayConfig?> = combine(_payConfig, prefs.seenAnnouncement) { c, seen ->
        if (c != null && c.announcementText.isNotBlank() && c.announcementId != seen) c else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun dismissAnnouncement() = viewModelScope.launch { _payConfig.value?.let { prefs.setSeenAnnouncement(it.announcementId) } }
    /** Sends a dead-channel report to the operator. Only reachable when the operator has reports on. */
    fun reportChannel(c: ChannelEntity, error: String?) = viewModelScope.launch {
        val server = codeServer.value
        val group = liveCategories.value.firstOrNull { it.id == c.categoryId }?.name
        val body = kotlinx.serialization.json.buildJsonObject {
            put("channel", kotlinx.serialization.json.JsonPrimitive(tv.sloopy.core.model.Names.clean(c.name)))
            put("number", kotlinx.serialization.json.JsonPrimitive(c.number.toString()))
            put("group", kotlinx.serialization.json.JsonPrimitive(group?.let { tv.sloopy.core.model.Names.clean(it) } ?: ""))
            put("error", kotlinx.serialization.json.JsonPrimitive(error ?: ""))
            put("device", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL))
            put("version", kotlinx.serialization.json.JsonPrimitive(tv.sloopy.BuildConfig.VERSION_NAME))
            put("nick", kotlinx.serialization.json.JsonPrimitive(syncCode.value?.take(4) ?: ""))
        }.toString()
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                tv.sloopy.data.AndroidHttp.client.newCall(
                    okhttp3.Request.Builder().url(server.trimEnd('/') + "/report")
                        .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)).build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        _toast.value = if (ok) "Reported ${tv.sloopy.core.model.Names.clean(c.name)}. Thanks." else "Could not send the report"
    }

    // ---- Self-healing and diagnosis -------------------------------------------------------------
    /** Copies already tried for the channel the viewer picked, so a dead cluster does not loop. */
    private var healTried = HashSet<Long>()
    private var healTarget: Long? = null
    private val _diagnosis = MutableStateFlow<tv.sloopy.data.Verdict?>(null)
    val diagnosis: StateFlow<tv.sloopy.data.Verdict?> = _diagnosis
    private val _healedFrom = MutableStateFlow<ChannelEntity?>(null)
    /** The copy that failed, when the one playing is a stand-in for it. */
    val healedFrom: StateFlow<ChannelEntity?> = _healedFrom
    init {
        player.onFailed = { _ -> onStreamFailed() }
        viewModelScope.launch { currentChannel.collect { c -> if (c != null && c.id != healTarget && dupes.value.byChannel[c.id]?.key != healTarget?.let { dupes.value.byChannel[it]?.key }) { healTried.clear(); healTarget = c.id; _healedFrom.value = null; _diagnosis.value = null } } }
    }
    private fun onStreamFailed() {
        val p = active.value ?: return
        val c = (playing.value as? Playing.Live)?.channel ?: return
        healTried.add(c.id)
        // Strong signal only: a copy in the same cluster (identical stripped name). Never a lookalike.
        val next = dupes.value.byChannel[c.id]?.members?.firstOrNull { it.id !in healTried }
        if (next != null) {
            _healedFrom.value = _healedFrom.value ?: c
            _toast.value = "Switched to ${tv.sloopy.core.model.Names.clean(next.name)} (backup copy). Hold OK › Copies to change."
            _playing.value = Playing.Live(next)
            runCatching { player.play(catalog.liveUrl(p, next), isLive = true) }
            return
        }
        // Nothing to switch to: find out whether it is you or them.
        viewModelScope.launch {
            _diagnosis.value = tv.sloopy.data.Verdict("Checking…", "Testing the connection, the provider and two other channels.")
            val others = visibleChannels.value.ifEmpty { channels.value }
            _diagnosis.value = runCatching { tv.sloopy.data.Diagnosis.run(p, codeServer.value, c, others) { ch -> runCatching { catalog.liveUrl(p, ch) }.getOrNull() } }
                .getOrElse { tv.sloopy.data.Verdict("Unclear", TLog.userMessage(it)) }
        }
    }
    fun undoHeal() { val from = _healedFrom.value ?: return; healTried.clear(); _healedFrom.value = null; tune(from) }

    // ---- Phone remote (keyboard + tap to tune) --------------------------------------------------
    private var remote: tv.sloopy.pair.RemoteServer? = null
    private val _remoteAddress = MutableStateFlow<String?>(null)
    val remoteAddress: StateFlow<String?> = _remoteAddress
    val remoteText = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    fun ensureRemote() {
        if (remote != null) return
        val s = tv.sloopy.pair.RemoteServer.start(getApplication(),
            channelsJson = {
                val list = visibleChannels.value.ifEmpty { collapse(channels.value) }
                buildString { append("{\"channels\":["); list.forEachIndexed { i, c -> if (i > 0) append(','); append("{\"id\":${c.id},\"number\":${c.number},\"name\":${kotlinx.serialization.json.JsonPrimitive(tv.sloopy.core.model.Names.clean(c.name))}}") }; append("]}") }
            },
            stateJson = { val c = currentChannel.value; "{\"id\":${c?.id ?: 0},\"toad\":${_toadAllowed.value},\"now\":${kotlinx.serialization.json.JsonPrimitive(c?.let { tv.sloopy.core.model.Names.clean(it.name) } ?: "")}}" },
        ) ?: return
        remote = s; _remoteAddress.value = s.address
        viewModelScope.launch {
            s.events.collect { ev ->
                when (ev) {
                    is tv.sloopy.pair.RemoteEvent.Type -> remoteText.tryEmit(ev.text to ev.submit)
                    is tv.sloopy.pair.RemoteEvent.Tune -> channels.value.firstOrNull { it.id == ev.channelId }?.let { tune(it); goHome() }
                    is tv.sloopy.pair.RemoteEvent.Toad -> { toadAsk(ev.text); toadEvents.tryEmit("toad") }
                }
            }
        }
    }

    // ---- Season passes ---------------------------------------------------------------------------
    val seasonPasses: StateFlow<List<String>> = perProvider({ prefs.seasonPasses(it) }, emptyList())
    val passUnsure: StateFlow<List<String>> = perProvider({ prefs.passUnsure(it) }, emptyList())
    private val scores = tv.sloopy.core.sports.Scores(tv.sloopy.data.AndroidHttp.core)
    fun addSeasonPass(team: String) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val t = team.trim(); if (t.isBlank()) return@launch
        prefs.setSeasonPasses(p.id, (seasonPasses.value + t).distinctBy { it.lowercase() })
        scanSeasonPasses(); _toast.value = "Season pass: $t. Games are booked as the guide fills in."
    }
    fun removeSeasonPass(team: String) = viewModelScope.launch { active.value?.let { prefs.setSeasonPasses(it.id, seasonPasses.value - team) } }
    fun resolveUnsure(entry: String, book: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        prefs.setPassUnsure(p.id, passUnsure.value - entry)
        if (!book) return@launch
        val parts = entry.split('|'); if (parts.size < 4) return@launch
        val c = channels.value.firstOrNull { it.id == parts[0].toLongOrNull() } ?: return@launch
        scheduleRecording(c, parts[3], parts[1].toLong(), parts[2].toLong())
    }
    private val SPORT = Regex("\\b(NFL|FOOTBALL|NCAA|COLLEGE|GAME|VS\\.?|V\\.?|AT|@|BASKETBALL|NBA|HOCKEY|NHL|MLB|BASEBALL)\\b", RegexOption.IGNORE_CASE)
    /** Books every upcoming game of each pass team the guide and ESPN's schedule agree on. */
    fun scanSeasonPasses() = viewModelScope.launch {
        val p = active.value ?: return@launch
        val teams = seasonPasses.value; if (teams.isEmpty()) return@launch
        val now = System.currentTimeMillis() / 1000
        val booked = prefs.passBooked(p.id).first().toMutableSet()
        val unsure = passUnsure.value.toMutableList()
        val hiddenCh = hiddenChannels.value; val hiddenG = hiddenGroups.value
        val visible = channels.value.filter { it.id !in hiddenCh && it.categoryId !in hiddenG }
        val byEpg = visible.filter { it.epgId != null }.groupBy { it.epgId!! }
        val counts = prefs.watchCounts(p.id).first()
        for (team in teams) {
            val progs = runCatching { catalog.searchProgrammes(p.id, team, now) }.getOrDefault(emptyList())
                .filter { it.startEpoch > now && it.startEpoch < now + 8 * 86_400 && SPORT.containsMatchIn(it.title) }
            // One booking per kickoff: the copy the viewer watches most, else the best quality.
            for ((slot, group) in progs.groupBy { it.startEpoch / 900 }) {
                val key = "$team|$slot"; if (key in booked) continue
                val cands = group.flatMap { pr -> (byEpg[pr.epgId] ?: emptyList()).map { pr to it } }
                val (pr, ch) = cands.maxByOrNull { (_, c) -> (counts[c.id] ?: 0) * 10 + tv.sloopy.core.model.Dupes.quality(c.name) } ?: continue
                val confirmed = runCatching { scheduleAgrees(team, pr.startEpoch) }.getOrNull()
                val entry = "${ch.id}|${pr.startEpoch}|${pr.stopEpoch}|${pr.title}"
                when (confirmed) {
                    true -> { scheduleRecording(ch, pr.title, pr.startEpoch, pr.stopEpoch); booked.add(key) }
                    else -> if (unsure.none { it == entry }) unsure.add(entry)
                }
            }
        }
        prefs.setPassBooked(p.id, booked.filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) * 900 > now - 86_400 }.toSet())
        prefs.setPassUnsure(p.id, unsure.filter { (it.split('|').getOrNull(1)?.toLongOrNull() ?: 0) > now }.take(40))
    }
    /** ESPN lists a game for this team starting within half an hour of the guide's time. Null when unreachable. */
    private suspend fun scheduleAgrees(team: String, startEpoch: Long): Boolean? {
        val day = java.time.Instant.ofEpochSecond(startEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val ymd = day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        var any = false
        for (league in listOf("nfl", "college-football")) {
            val games = runCatching { scores.games(league, ymd) }.getOrNull() ?: continue
            any = true
            val t = team.lowercase()
            if (games.any { g -> (g.home.lowercase().contains(t) || g.away.lowercase().contains(t) || t.contains(g.homeShort.lowercase()) && t.length <= 4) && kotlin.math.abs(g.startEpoch - startEpoch) < 1800 }) return true
        }
        return if (any) false else null
    }
    init {
        viewModelScope.launch { catalog.refresh.collect { if (it is tv.sloopy.data.RefreshState.Done) scanSeasonPasses() } }
        viewModelScope.launch { while (true) { delay(6 * 3600_000L); scanSeasonPasses() } }
    }

    // ---- Tonight ---------------------------------------------------------------------------------
    data class TonightItem(val kind: String, val title: String, val sub: String, val channel: ChannelEntity?, val prog: tv.sloopy.data.db.ProgrammeEntity?, val vodId: Long?, val vodKind: String?, val image: String?, val year: String?, val score: Int)
    private val _tonight = MutableStateFlow<List<TonightItem>>(emptyList())
    val tonight: StateFlow<List<TonightItem>> = _tonight
    val tonightSportsAnywhere: StateFlow<Boolean> = prefs.tonightSportsAnywhere.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tonightNewOnDemand: StateFlow<Boolean> = prefs.tonightNewOnDemand.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tonightPopular: StateFlow<Boolean> = prefs.tonightPopular.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    data class Trend(val title: String, val year: String?, val poster: String?)
    private var trendCache: Pair<Long, Pair<List<Trend>, List<Trend>>>? = null
    /** TMDB's trending shows and films for today, via the code server (one key, cached there six hours, here one). */
    private suspend fun trending(): Pair<List<Trend>, List<Trend>> {
        trendCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 3_600_000) return v }
        val server = codeServer.value; if (!server.startsWith("http")) return emptyList<Trend>() to emptyList()
        val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                tv.sloopy.data.AndroidHttp.client.newCall(okhttp3.Request.Builder().url(server.trimEnd('/') + "/trending").get().build()).execute().use { r ->
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string() ?: "") as kotlinx.serialization.json.JsonObject
                    fun list(k: String) = (root[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e ->
                        val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        fun str(n: String) = (o[n] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        str("title")?.let { Trend(it, str("year")?.ifBlank { null }, str("poster")) }
                    } ?: emptyList()
                    list("tv") to list("movies")
                }
            }.getOrDefault(emptyList<Trend>() to emptyList())
        }
        if (v.first.isNotEmpty() || v.second.isNotEmpty()) trendCache = System.currentTimeMillis() to v
        return v
    }
    fun notInterested(title: String) = viewModelScope.launch { prefs.addTonightNotInterested(title); buildTonight() }
    private val _tonightBusy = MutableStateFlow(false)
    val tonightBusy: StateFlow<Boolean> = _tonightBusy
    fun buildTonight() = viewModelScope.launch {
        val p = active.value ?: return@launch
        _tonightBusy.value = true
        try { buildTonightNow(p) } finally { _tonightBusy.value = false }
    }
    private suspend fun buildTonightNow(p: tv.sloopy.data.ProviderRecord) {
        val now = System.currentTimeMillis() / 1000
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val from = maxOf(now, today.atTime(18, 0).atZone(zone).toEpochSecond())
        val to = today.plusDays(1).atTime(0, 30).atZone(zone).toEpochSecond()
        val no = prefs.tonightNotInterested.first()
        val counts = prefs.watchCounts(p.id).first()
        val favs = favouriteIds.value
        val hiddenCh = hiddenChannels.value; val hiddenG = hiddenGroups.value
        val all = channels.value.filter { it.id !in hiddenCh && it.categoryId !in hiddenG }
        // The pool: channels actually watched, favourites, and (optionally) any channel carrying sport.
        val pool = all.filter { (counts[it.id] ?: 0) > 0 || it.id in favs }.distinctBy { dupes.value.byChannel[it.id]?.key ?: "#${it.id}" }.take(40)
        val items = ArrayList<TonightItem>()
        val winStart = guideWindowStart(now)
        for (c in pool) {
            val progs = programmesFor(c, winStart).filter { it.stopEpoch > from && it.startEpoch < to && it.startEpoch >= from - 1800 }
            for (pr in progs.take(3)) {
                if (pr.title.lowercase() in no) continue
                val sport = SPORT.containsMatchIn(pr.title) && pr.stopEpoch - pr.startEpoch >= 5400
                val score = (counts[c.id] ?: 0) * 10 + (if (c.id in favs) 5 else 0) + (if (sport) 8 else 0) + (if (pr.stopEpoch - pr.startEpoch >= 5400) 3 else 0)
                items.add(TonightItem("live", pr.title, tv.sloopy.core.model.Names.clean(c.name) + " · " + tv.sloopy.ui.common.TimeFmt.hhmm(pr.startEpoch), c, pr, null, null, null, null, score))
            }
        }
        // Everything on tonight across the whole lineup, read once; the sport pass and the popular
        // pass match titles against it in memory. (A LIKE per title over a million rows was minutes.)
        val byEpg = all.filter { it.epgId != null }.groupBy { it.epgId!! }
        val tonightRows: List<tv.sloopy.data.db.ProgrammeEntity> = if (tonightSportsAnywhere.value || tonightPopular.value) runCatching { catalog.programmesBetween(p.id, from, to) }.getOrDefault(emptyList()).filter { it.epgId in byEpg } else emptyList()
        if (tonightSportsAnywhere.value) {
            val seen = items.map { it.title.lowercase() }.toMutableSet()
            for (word in listOf("NFL", "College Football", "NBA", "NHL")) {
                val w = word.lowercase()
                tonightRows.filter { it.startEpoch in from..to && it.title.lowercase().let { t -> t.contains(w) && t !in no } }.sortedBy { it.startEpoch }.take(6).forEach { pr ->
                    val c = byEpg[pr.epgId]?.firstOrNull() ?: return@forEach
                    if (seen.add(pr.title.lowercase())) items.add(TonightItem("live", pr.title, tv.sloopy.core.model.Names.clean(c.name) + " · " + tv.sloopy.ui.common.TimeFmt.hhmm(pr.startEpoch), c, pr, null, null, null, null, 6))
                }
            }
        }
        for (r in reminders.value.filter { it.startEpoch in from..to }) items.add(TonightItem("reminder", r.title, "Reminder · " + r.channelName + " · " + tv.sloopy.ui.common.TimeFmt.hhmm(r.startEpoch), all.firstOrNull { it.id == r.channelId }, null, null, null, null, null, 100))
        if (tonightNewOnDemand.value) {
            val cutoff = now - 2 * 86_400
            val mine = favouriteSeriesIds.value
            runCatching { graph.db.series().recent(p.id, 60) }.getOrDefault(emptyList()).filter { it.id in mine && (it.lastModifiedEpoch ?: 0) > cutoff }.take(4)
                .forEach { items.add(TonightItem("series", it.name, "New episode · My list", null, null, it.id, "series", it.cover, it.releaseDate?.take(4), 20)) }
        }
        if (tonightPopular.value) {
            // Popular tonight: TMDB's trending titles, kept only when this service carries them (on a
            // channel tonight, or in the film/series library). Never more than four tiles.
            val (shows, films) = trending()
            val seen = items.map { it.title.lowercase() }.toMutableSet()
            val popular = ArrayList<TonightItem>()
            for (t in shows.take(30)) {
                if (popular.size >= 4) break
                if (t.title.lowercase() in no || t.title.lowercase() in seen) continue
                val hit = tonightRows.filter { tv.sloopy.core.model.Names.sameTitle(it.title, t.title) }.minByOrNull { it.startEpoch }
                if (hit != null) {
                    val c = byEpg[hit.epgId]!!.first()
                    popular.add(TonightItem("popular", t.title, tv.sloopy.core.model.Names.clean(c.name) + " · " + tv.sloopy.ui.common.TimeFmt.hhmm(hit.startEpoch), c, hit, null, null, t.poster, t.year, 4))
                    seen.add(t.title.lowercase()); continue
                }
                val s = runCatching { graph.db.series().search(p.id, t.title, 20) }.getOrDefault(emptyList()).firstOrNull { tv.sloopy.core.model.Names.sameTitle(it.name, t.title) }
                if (s != null) { popular.add(TonightItem("popular", t.title, "Popular · Series", null, null, s.id, "series", s.cover ?: t.poster, t.year, 3)); seen.add(t.title.lowercase()) }
            }
            for (t in films.take(30)) {
                if (popular.size >= 4) break
                if (t.title.lowercase() in no || t.title.lowercase() in seen) continue
                val v = runCatching { graph.db.vod().search(p.id, t.title, 20) }.getOrDefault(emptyList()).firstOrNull { tv.sloopy.core.model.Names.sameTitle(it.name, t.title) }
                if (v != null) { popular.add(TonightItem("popular", t.title, "Popular · Movies", null, null, v.id, "vod", v.poster ?: t.poster, t.year, 3)); seen.add(t.title.lowercase()) }
            }
            items.addAll(popular)
        }
        _tonight.value = items.sortedByDescending { it.score }.distinctBy { it.kind + it.title.lowercase() }.take(12)
    }
    /** Poster art through the code server (one TMDB key, shared cache). Null when none. */
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun poster(title: String, year: String?, kind: String): String? {
        val k = "$kind|$title|$year"
        posterCache[k]?.let { return it.ifEmpty { null } }
        val server = codeServer.value; if (!server.startsWith("http")) return null
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val q = server.trimEnd('/') + "/poster?kind=" + kind + "&title=" + java.net.URLEncoder.encode(title, "UTF-8") + (if (year.isNullOrBlank()) "" else "&year=$year")
                tv.sloopy.data.AndroidHttp.client.newCall(okhttp3.Request.Builder().url(q).get().build()).execute().use { r ->
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string() ?: "") as? kotlinx.serialization.json.JsonObject
                    (root?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
            }.getOrNull()
        }
        posterCache[k] = url ?: ""
        return url
    }

    // ---- The Toad --------------------------------------------------------------------------------
    // One sentence from the viewer becomes a plan from a fixed menu of actions; the TV resolves the
    // names itself, shows the plan, and runs it only after OK. The Claude key lives on the code
    // server; this stick is known there by its friend code.
    data class ToadAction(val type: String, val names: List<String>, val title: String, val query: String)
    data class ToadPlan(val asked: String, val say: String, val lines: List<String>, val runnable: Boolean)
    private class ToadStep(val line: String, val run: suspend () -> Unit)
    private val _toadAllowed = MutableStateFlow(false)
    val toadAllowed: StateFlow<Boolean> = _toadAllowed
    private val _toadBusy = MutableStateFlow(false)
    val toadBusy: StateFlow<Boolean> = _toadBusy
    private val _toadPlan = MutableStateFlow<ToadPlan?>(null)
    val toadPlan: StateFlow<ToadPlan?> = _toadPlan
    private val _toadError = MutableStateFlow<String?>(null)
    val toadError: StateFlow<String?> = _toadError
    /** A route to open because the phone or a plan asked for it ("toad", "search"). */
    val toadEvents = MutableSharedFlow<String>(extraBufferCapacity = 2)
    private var toadSteps: List<ToadStep> = emptyList()
    val groupTags: StateFlow<Map<String, String>> = perProvider({ prefs.groupTags(it) }, emptyMap())

    private suspend fun toadPost(path: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
        val server = codeServer.value; if (!server.startsWith("http")) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(server.trimEnd('/') + path)
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString())).build()
                tv.sloopy.data.AndroidHttp.client.newCall(req).execute().use { r ->
                    kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string() ?: "") as? kotlinx.serialization.json.JsonObject
                }
            }.getOrNull()
        }
    }
    private fun kotlinx.serialization.json.JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun kotlinx.serialization.json.JsonObject.bool(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true"

    /** Asks the code server whether this stick may use the Toad; shows the strip icon when it may. */
    fun refreshToad() = viewModelScope.launch {
        ensureFriendCode()
        val code = kotlinx.coroutines.withTimeoutOrNull(5_000) { friendCode.first { it != null } } ?: return@launch
        val server = codeServer.value; if (!server.startsWith("http")) return@launch
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                tv.sloopy.data.AndroidHttp.client.newCall(okhttp3.Request.Builder().url(server.trimEnd('/') + "/toad/status?id=" + code).get().build()).execute().use { res ->
                    kotlinx.serialization.json.Json.parseToJsonElement(res.body?.string() ?: "") as? kotlinx.serialization.json.JsonObject
                }
            }.getOrNull()
        } ?: return@launch
        val on = r.bool("enabled") && r.bool("allowed")
        _toadAllowed.value = on
        tv.sloopy.ui.common.ToadFlag.on.value = on
        if (on) refreshGroupTags()
    }

    /** One Claude call tags every live group (sports, kids, adult, foreign, ...). Cached per name set. */
    fun refreshGroupTags() = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cats = kotlinx.coroutines.withTimeoutOrNull(60_000) { allLiveCategories.first { it.isNotEmpty() } } ?: return@launch
        val names = cats.map { tv.sloopy.core.model.Names.clean(it.name) }.distinct()
        val digest = names.sorted().joinToString("\n").hashCode().toString()
        if (prefs.groupTagsDigest(p.id).first() == digest) return@launch
        val body = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(friendCode.value ?: ""))
            put("groups", kotlinx.serialization.json.JsonArray(names.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        val r = toadPost("/toad/tags", body) ?: return@launch
        if (!r.bool("ok")) return@launch
        val got = r["tags"] as? kotlinx.serialization.json.JsonObject ?: return@launch
        val byId = cats.associate { c -> c.id to (got.str(tv.sloopy.core.model.Names.clean(c.name)) ?: "general") }
        prefs.setGroupTags(p.id, byId, digest)
    }
    fun groupsTagged(tag: String): List<CategoryEntity> = allLiveCategories.value.filter { groupTags.value[it.id] == tag }
    fun hideTagged(tag: String, hide: Boolean) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val cur = hiddenGroups.value.toMutableSet()
        val ids = groupsTagged(tag).map { it.id }
        if (hide) cur.addAll(ids) else cur.removeAll(ids.toSet())
        prefs.setHiddenGroups(p.id, cur)
        if (hide && _group.value in ids) selectGroup(Groups.ALL)
    }

    private fun toadFindChannel(name: String): ChannelEntity? {
        val hiddenG = hiddenGroups.value; val hiddenCh = hiddenChannels.value
        val pool = channels.value.filter { it.categoryId !in hiddenG && it.id !in hiddenCh }
        val want = name.trim().lowercase()
        fun clean(c: ChannelEntity) = tv.sloopy.core.model.Names.clean(c.name).lowercase()
        val exact = pool.filter { clean(it) == want }
        val tagged = if (exact.isNotEmpty()) exact else pool.filter { tv.sloopy.core.model.Names.sameTitle(clean(it), want) }
        val loose = if (tagged.isNotEmpty()) tagged else pool.filter { c -> val n = clean(c); n.contains(want) && (n.indexOf(want) == 0 || !n[n.indexOf(want) - 1].isLetterOrDigit()) }
        return collapse(loose).minByOrNull { clean(it).length }
    }
    private fun toadFindGroup(name: String): CategoryEntity? {
        val want = name.trim().lowercase()
        val cats = allLiveCategories.value
        return cats.firstOrNull { tv.sloopy.core.model.Names.clean(it.name).equals(want, true) }
            ?: cats.firstOrNull { tv.sloopy.core.model.Names.clean(it.name).lowercase().contains(want) }
    }

    fun toadAsk(text: String) = viewModelScope.launch {
        val t = text.trim(); if (t.isEmpty()) return@launch
        _toadBusy.value = true; _toadError.value = null; _toadPlan.value = null; toadSteps = emptyList()
        prefs.pushToad(t)
        val favs = favouriteIds.value
        val favNames = channels.value.filter { it.id in favs }.map { tv.sloopy.core.model.Names.clean(it.name) }.distinct().take(60)
        val groups = allLiveCategories.value.map { tv.sloopy.core.model.Names.clean(it.name) }.distinct()
        val body = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(friendCode.value ?: ""))
            put("text", kotlinx.serialization.json.JsonPrimitive(t))
            put("groups", kotlinx.serialization.json.JsonArray(groups.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("favourites", kotlinx.serialization.json.JsonArray(favNames.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("current", kotlinx.serialization.json.JsonPrimitive(currentChannel.value?.let { tv.sloopy.core.model.Names.clean(it.name) } ?: ""))
            put("now", kotlinx.serialization.json.JsonPrimitive(tv.sloopy.ui.common.TimeFmt.dateTime(System.currentTimeMillis())))
        }
        val r = toadPost("/toad/ask", body)
        _toadBusy.value = false
        if (r == null) { _toadError.value = "Could not reach the Toad. Check the connection."; return@launch }
        if (!r.bool("ok")) { _toadError.value = r.str("error") ?: "The Toad mumbled. Try again."; return@launch }
        val actions = (r["actions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            ToadAction(o.str("type") ?: "nothing", (o["names"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: emptyList(), o.str("title") ?: "", o.str("query") ?: "")
        } ?: emptyList()
        val steps = ArrayList<ToadStep>(); val lines = ArrayList<String>()
        val now = System.currentTimeMillis() / 1000
        val p = active.value
        fun channelsFor(a: ToadAction, verb: String): List<ChannelEntity> {
            val found = ArrayList<ChannelEntity>()
            for (n in a.names) { val c = toadFindChannel(n); if (c != null) found.add(c) else lines.add("$n: not on this service") }
            if (found.isNotEmpty()) lines.add(verb + " " + found.joinToString(", ") { tv.sloopy.core.model.Names.clean(it.name) })
            return found
        }
        for (a in actions) when (a.type) {
            "favourite_add" -> { val cs = channelsFor(a, "Add to Favourites:").filter { it.id !in favs }; if (cs.isNotEmpty()) steps.add(ToadStep("", { cs.forEach { toggleFavourite(it) } })) }
            "favourite_remove" -> { val cs = channelsFor(a, "Remove from Favourites:").filter { it.id in favs }; if (cs.isNotEmpty()) steps.add(ToadStep("", { cs.forEach { toggleFavourite(it) } })) }
            "hide_channel", "show_channel" -> { val hide = a.type == "hide_channel"; val cs = channelsFor(a, if (hide) "Hide:" else "Show:"); if (cs.isNotEmpty()) steps.add(ToadStep("", { cs.forEach { setChannelHidden(it.id, hide) } })) }
            "hide_group", "show_group" -> {
                val hide = a.type == "hide_group"
                val gs = a.names.mapNotNull { n -> toadFindGroup(n).also { if (it == null) lines.add("$n: no such group") } }.distinctBy { it.id }
                if (gs.isNotEmpty()) { lines.add((if (hide) "Hide " else "Show ") + gs.size + (if (gs.size == 1) " group: " else " groups: ") + gs.take(8).joinToString(", ") { tv.sloopy.core.model.Names.clean(it.name) } + (if (gs.size > 8) "…" else "")); steps.add(ToadStep("", { gs.forEach { setHidden(it.id, hide) } })) }
            }
            "tune" -> { val c = a.names.firstOrNull()?.let { toadFindChannel(it) }; if (c != null) { lines.add("Switch to " + tv.sloopy.core.model.Names.clean(c.name)); steps.add(ToadStep("", { tune(c); goHome() })) } else lines.add((a.names.firstOrNull() ?: "That channel") + ": not on this service") }
            "remind", "record" -> {
                val title = a.title.ifBlank { a.names.firstOrNull() ?: "" }
                val pr = if (p != null && title.isNotBlank()) runCatching { catalog.searchProgrammes(p.id, title, now) }.getOrDefault(emptyList())
                    .filter { it.stopEpoch > now }.sortedBy { it.startEpoch }.firstOrNull { pr -> channels.value.any { it.epgId == pr.epgId && it.categoryId !in hiddenGroups.value } } else null
                val c = pr?.let { pr2 -> channels.value.firstOrNull { it.epgId == pr2.epgId && it.categoryId !in hiddenGroups.value } }
                when {
                    pr != null && c != null && a.type == "remind" -> { lines.add("Remind: " + pr.title + " · " + tv.sloopy.core.model.Names.clean(c.name) + " · " + tv.sloopy.ui.common.TimeFmt.dateTime(pr.startEpoch * 1000)); steps.add(ToadStep("", { addReminder(c, pr.title, pr.startEpoch, pr.stopEpoch) })) }
                    pr != null && c != null -> { lines.add("Record: " + pr.title + " · " + tv.sloopy.core.model.Names.clean(c.name) + " · " + tv.sloopy.ui.common.TimeFmt.dateTime(pr.startEpoch * 1000)); steps.add(ToadStep("", { scheduleRecording(c, pr.title, pr.startEpoch, pr.stopEpoch) })) }
                    a.type == "record" && title.isBlank() && currentChannel.value != null -> { val cc = currentChannel.value!!; lines.add("Record what is on " + tv.sloopy.core.model.Names.clean(cc.name) + " now"); steps.add(ToadStep("", { recordNow(cc) })) }
                    else -> lines.add((title.ifBlank { "That" }) + ": nothing in the guide yet")
                }
            }
            "search" -> { val q = a.query.ifBlank { t }; lines.add("Search for \"" + q + "\""); steps.add(ToadStep("", { lastSearch = q; toadEvents.tryEmit("search") })) }
            else -> {}
        }
        toadSteps = steps
        _toadPlan.value = ToadPlan(t, r.str("say") ?: "", lines, steps.isNotEmpty())
    }
    fun toadRun() = viewModelScope.launch {
        val steps = toadSteps; toadSteps = emptyList(); _toadPlan.value = null
        for (st in steps) runCatching { st.run() }
        if (steps.isNotEmpty() && steps.none { it.line == "nav" }) toast("Done.")
    }
    fun toadDismiss() { toadSteps = emptyList(); _toadPlan.value = null; _toadError.value = null }

    // ---- Friends ---------------------------------------------------------------------------------
    data class Presence(val code: String, val name: String, val channel: String?, val at: Long)
    val friendCode: StateFlow<String?> = prefs.friendCode.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val friendNick: StateFlow<String> = prefs.friendNick.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val shareWatching: StateFlow<Boolean> = prefs.shareWatching.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val friends: StateFlow<List<Pair<String, String>>> = prefs.friends.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _presence = MutableStateFlow<List<Presence>>(emptyList())
    val presence: StateFlow<List<Presence>> = _presence
    private val _incoming = MutableStateFlow<kotlinx.serialization.json.JsonObject?>(null)
    val incoming: StateFlow<kotlinx.serialization.json.JsonObject?> = _incoming
    private suspend fun friendsCall(path: String, body: String?): kotlinx.serialization.json.JsonObject? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val server = codeServer.value; if (!server.startsWith("http")) return@withContext null
        runCatching {
            val b = okhttp3.Request.Builder().url(server.trimEnd('/') + path)
            if (body != null) b.post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)) else b.get()
            tv.sloopy.data.AndroidHttp.client.newCall(b.build()).execute().use { r -> kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string() ?: "") as? kotlinx.serialization.json.JsonObject }
        }.getOrNull()
    }
    fun ensureFriendCode() = viewModelScope.launch { if (friendCode.value == null) prefs.setFriendCode(tv.sloopy.sync.SyncClient.newCode()) }
    fun addFriend(code: String, name: String) = viewModelScope.launch {
        val c = tv.sloopy.sync.SyncClient.normalise(code); if (c.length != 8) { _toast.value = "A friend code is eight letters and numbers"; return@launch }
        prefs.setFriends((friends.value.filter { it.first != c } + (c to name.trim().ifBlank { c })))
        refreshPresence()
    }
    fun removeFriend(code: String) = viewModelScope.launch { prefs.setFriends(friends.value.filter { it.first != code }) }
    fun refreshPresence() = viewModelScope.launch {
        val list = friends.value; if (list.isEmpty()) { _presence.value = emptyList(); return@launch }
        val root = friendsCall("/friends/list?ids=" + list.joinToString(",") { it.first }, null) ?: return@launch
        val arr = root["friends"] as? kotlinx.serialization.json.JsonArray ?: return@launch
        _presence.value = list.map { (code, name) ->
            val o = arr.firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("id")?.let { id -> (id as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } == code } as? kotlinx.serialization.json.JsonObject
            Presence(code, name, (o?.get("channel") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }, (o?.get("at") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L)
        }
    }
    fun sendToFriend(code: String, c: ChannelEntity) = viewModelScope.launch {
        val me = friendCode.value ?: return@launch
        val body = kotlinx.serialization.json.buildJsonObject {
            put("to", kotlinx.serialization.json.JsonPrimitive(code)); put("from", kotlinx.serialization.json.JsonPrimitive(me)); put("nick", kotlinx.serialization.json.JsonPrimitive(friendNick.value))
            put("channel", kotlinx.serialization.json.JsonPrimitive(tv.sloopy.core.model.Names.clean(c.name))); put("key", kotlinx.serialization.json.JsonPrimitive(tv.sloopy.core.model.Dupes.key(c.name)))
        }.toString()
        _toast.value = if (friendsCall("/friends/send", body)?.get("ok")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } == "true") "Sent to ${friends.value.firstOrNull { it.first == code }?.second ?: code}" else "Could not send"
    }
    fun joinFriend(p: Presence) {
        val name = p.channel ?: return
        val key = tv.sloopy.core.model.Dupes.key(name)
        val c = channels.value.firstOrNull { tv.sloopy.core.model.Dupes.key(it.name) == key } ?: run { _toast.value = "You do not have $name"; return }
        tune(dupes.value.byChannel[c.id]?.primary ?: c); goHome()
    }
    fun acceptIncoming(watch: Boolean) {
        val m = _incoming.value; _incoming.value = null
        if (!watch || m == null) return
        val key = (m["key"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        val c = channels.value.firstOrNull { tv.sloopy.core.model.Dupes.key(it.name) == key } ?: run { _toast.value = "You do not have that channel"; return }
        tune(dupes.value.byChannel[c.id]?.primary ?: c); goHome()
    }
    init {
        viewModelScope.launch {
            while (true) {
                delay(45_000)
                val me = friendCode.value
                if (me != null && shareWatching.value) {
                    val c = currentChannel.value?.let { tv.sloopy.core.model.Names.clean(it.name) } ?: ""
                    friendsCall("/friends/me", kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(me)); put("nick", kotlinx.serialization.json.JsonPrimitive(friendNick.value)); put("channel", kotlinx.serialization.json.JsonPrimitive(c)) }.toString())
                }
                if (me != null && friends.value.isNotEmpty()) {
                    val inbox = friendsCall("/friends/inbox?id=$me", null)
                    val first = (inbox?.get("messages") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject
                    if (first != null && _incoming.value == null) _incoming.value = first
                    if (friends.value.isNotEmpty()) refreshPresence()
                }
            }
        }
    }

    // ---- Profiles --------------------------------------------------------------------------------
    fun saveProfile(pr: Profile) = viewModelScope.launch {
        val list = profiles.value.filter { it.id != pr.id } + pr
        prefs.setProfiles(profileJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), list))
    }
    fun deleteProfile(id: String) = viewModelScope.launch {
        prefs.setProfiles(profileJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), profiles.value.filter { it.id != id }))
        if (prefs.activeProfile.first() == id) prefs.setActiveProfile("")
    }
    fun switchProfile(id: String) = viewModelScope.launch { prefs.setActiveProfile(id); selectGroup(Groups.ALL); goHome() }
    /** Category ids a profile allows for a library kind; null = everything. */
    fun allowedCats(kind: String): List<String>? = activeProfile.value?.let { if (kind == "vod") it.vodCats else it.seriesCats }

    // ---- Scores ----------------------------------------------------------------------------------
    // ESPN's public feeds, read straight from the stick. Games carry ESPN team ids, so "my teams"
    // matches on ids, never on names. The channel for a game is found in the viewer's own lineup:
    // the programme on now whose title names both teams, else the network map, else a guess.
    data class GameRow(val game: tv.sloopy.core.sports.Game, val channel: ChannelEntity?, val via: String)
    data class FollowedTeam(val league: String, val id: String, val name: String, val abbr: String, val logo: String?) {
        fun encode() = listOf(league, id, name, abbr, logo ?: "").joinToString("|")
        companion object {
            fun decode(s: String): FollowedTeam? { val p = s.split('|'); return if (p.size < 4) null else FollowedTeam(p[0], p[1], p[2], p[3], p.getOrNull(4)?.ifBlank { null }) }
            fun of(t: tv.sloopy.core.sports.Team) = FollowedTeam(t.league, t.id, t.name, t.abbr, t.logo)
        }
    }
    private val _games = MutableStateFlow<List<GameRow>>(emptyList())
    val games: StateFlow<List<GameRow>> = _games
    private val _scoresBusy = MutableStateFlow(false)
    val scoresBusy: StateFlow<Boolean> = _scoresBusy
    /** Days from today for the games shown: -1 yesterday … +6. */
    val scoresDay = MutableStateFlow(0)
    val followedTeams: StateFlow<List<FollowedTeam>> = prefs.followedTeams.map { l -> l.mapNotNull { FollowedTeam.decode(it) } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun follows(league: String, id: String) = followedTeams.value.any { it.league == league && it.id == id }
    fun toggleFollow(t: FollowedTeam) = viewModelScope.launch {
        val cur = followedTeams.value
        val next = if (cur.any { it.league == t.league && it.id == t.id }) cur.filterNot { it.league == t.league && it.id == t.id } else cur + t
        prefs.setFollowedTeams(next.map { it.encode() })
        _toast.value = if (next.size > cur.size) "Following the ${t.name}" else "No longer following the ${t.name}"
    }
    val hideScores: StateFlow<Boolean> = prefs.hideScores.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setHideScores(v: Boolean) = viewModelScope.launch { prefs.setHideScores(v) }
    val networkMap: StateFlow<Map<String, Long>> = perProvider({ prefs.networkMap(it) }, emptyMap())
    /** game id → (channel id, how it was found, when). */
    private val gameChannelCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long?, String, Long>>()
    /** Groups and channel keys that could carry a game: where the day's listings are read for the sport pass. */
    private val SPORT_WORDS = Regex("SPORT|NFL|NBA|NHL|MLB|NCAA|COLLEGE|ESPN|PPV|EVENT|GAME|PEACOCK|\\bFOX\\b|\\bCBS\\b|\\bNBC\\b|\\bABC\\b|\\bTNT\\b|\\bTBS\\b|\\bUSA\\b|\\bBTN\\b|\\bSEC\\b|\\bACC\\b")
    /**
     * Sports opens: if the channel list is over an hour old, pull it again (names only, no
     * library, no guide) and resolve the games afresh. Providers rename event feeds to the
     * matchup on game day, and the usual refresh is once a day at launch.
     */
    fun refreshChannelsIfStale() = viewModelScope.launch {
        val p = active.value ?: return@launch
        if (p.kind == tv.sloopy.data.ProviderKind.DEMO) return@launch
        val age = System.currentTimeMillis() - prefs.refreshedAt(p.id, "channels")
        if (age < 3600_000L) return@launch
        runCatching { catalog.refreshAll(p, includeLibrary = false, includeEpg = false) }.onFailure { TLog.w { "sports channel refresh: ${TLog.userMessage(it)}" } }
        gameChannelCache.clear(); refreshScores()
    }
    fun setNetworkChannel(network: String, c: ChannelEntity?) = viewModelScope.launch {
        val p = active.value ?: return@launch
        val m = networkMap.value.toMutableMap(); if (c == null) m.remove(network) else m[network] = c.id
        prefs.setNetworkMap(p.id, m); gameChannelCache.clear(); refreshScores()
    }
    /**
     * Channels that carry the network, best first: local affiliates (city and state) over the
     * bare national feed, look-alikes such as FOX SOUL or ABC NEWS left out (`Affiliates`).
     * The picker's list and the guess share this order.
     */
    fun channelsForNetwork(network: String, from: List<ChannelEntity> = channels.value, keyOf: (ChannelEntity) -> String = { tv.sloopy.core.model.Dupes.key(it.name) }): List<ChannelEntity> {
        val words = tv.sloopy.core.sports.Scores.NETWORK_WORDS[network] ?: listOf(network)
        val ranked = from.mapNotNull { c -> tv.sloopy.core.sports.Affiliates.rank(network, keyOf(c), words)?.let { c to it } }
        val usCats = liveCategories.value.filter { it.name.uppercase().contains("US") }.map { it.id }.toSet()
        val order = ranked.sortedWith(compareByDescending<Pair<ChannelEntity, Int>> { it.second }.thenByDescending { it.first.categoryId in usCats })
        return collapse(order.map { it.first }).take(30)
    }
    fun setScoresDay(d: Int) { if (scoresDay.value != d) { scoresDay.value = d; refreshScores() } }
    private var scoresJob: Job? = null
    fun refreshScores() {
        scoresJob?.cancel()
        scoresJob = viewModelScope.launch {
            _scoresBusy.value = true
            try {
                val p = active.value
                val day = java.time.LocalDate.now().plusDays(scoresDay.value.toLong())
                val ymd = day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                val all = ArrayList<tv.sloopy.core.sports.Game>()
                for (league in tv.sloopy.core.sports.Scores.LEAGUES) runCatching { scores.games(league, ymd) }.getOrNull()?.let { all.addAll(it) }
                val sorted = all.sortedWith(compareBy({ when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }, { !it.redZone }, { it.startEpoch }))
                val rows = if (p == null) sorted.map { GameRow(it, null, "") } else resolveGameChannels(p, sorted)
                _games.value = rows
            } finally { _scoresBusy.value = false }
        }
    }
    /** One pass over what is on now, then the network map, then a guess by name: never a search through the guide table. */
    private suspend fun resolveGameChannels(p: tv.sloopy.data.ProviderRecord, list: List<tv.sloopy.core.sports.Game>): List<GameRow> {
        val now = System.currentTimeMillis() / 1000
        val hiddenCh = hiddenChannels.value; val hiddenG = hiddenGroups.value
        val live = channels.value.filter { it.id !in hiddenCh && it.categoryId !in hiddenG }
        val byEpg = live.filter { it.epgId != null }.groupBy { it.epgId!! }
        // The guide at each kickoff (quarter-hour buckets, at least now), for the channels that
        // could carry a game: sports groups and sports-named channels. A few indexed reads per
        // refresh instead of one over every channel for the two hours around now.
        val sportCats = liveCategories.value.filter { SPORT_WORDS.containsMatchIn(it.name.uppercase()) }.map { it.id }.toSet()
        val sporty = live.filter { it.epgId != null && (it.categoryId in sportCats || SPORT_WORDS.containsMatchIn(it.name.uppercase())) }.map { it.epgId!! }.distinct()
        fun bucket(g: tv.sloopy.core.sports.Game) = maxOf(g.startEpoch + 60, now) / 900 * 900
        val listingAt = HashMap<Long, Map<String, tv.sloopy.data.db.NowNext>>()
        if (sporty.isNotEmpty()) for (t in list.map { bucket(it) }.distinct().sorted().take(16)) listingAt[t] = runCatching { catalog.nowPlaying(p.id, sporty, t) }.getOrDefault(emptyMap())
        // Off the main thread: a Saturday has eighty games, and the name scan below walks every
        // channel. Each channel's key is computed once, and each network's candidates once, per refresh.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val keyOf = HashMap<Long, String>(live.size * 2)
            live.forEach { keyOf[it.id] = tv.sloopy.core.model.Dupes.key(it.name) }
            val candidates = HashMap<String, List<ChannelEntity>>()
            fun forNetwork(net: String): List<ChannelEntity> = candidates.getOrPut(net) { channelsForNetwork(net, live) { keyOf[it.id] ?: "" } }
            // Channel names, normalised once: ESPN+ and PPV event feeds are named after the
            // matchup ("ESPN+ 14: Bowling Green vs Toledo"), with no guide row behind them.
            val nameNorm = HashMap<Long, String>(live.size * 2)
            live.forEach { nameNorm[it.id] = tv.sloopy.core.sports.Scores.normalize(tv.sloopy.core.model.Names.clean(it.name)) }
            // The guide at the game's start, for the network's candidates: "PEACOCK 3" is the
            // one whose listing (or name) names both teams. One small indexed read per game that needs it.
            suspend fun byListing(g: tv.sloopy.core.sports.Game, cands: List<ChannelEntity>): ChannelEntity? {
                val ids = cands.mapNotNull { it.epgId }.distinct()
                val at = maxOf(g.startEpoch + 60, now)
                val listing = if (ids.isEmpty()) emptyMap() else runCatching { catalog.nowPlaying(p.id, ids, at) }.getOrDefault(emptyMap())
                return cands.map { c ->
                    val fromName = tv.sloopy.core.sports.Scores.score(nameNorm[c.id] ?: " ", g)
                    val fromGuide = listing[c.epgId]?.let { tv.sloopy.core.sports.Scores.mentions(it.title, g) } ?: 0
                    c to maxOf(fromName, fromGuide)
                }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            }
            list.map { g ->
                gameChannelCache[g.id]?.let { (id, via, at) -> if (now - at < 600) return@map GameRow(g, live.firstOrNull { it.id == id }, via) }
                val fromGuide = (listingAt[bucket(g)] ?: emptyMap()).entries.filter { (_, nn) -> tv.sloopy.core.sports.Scores.mentions(nn.title, g) == 2 }
                    .flatMap { byEpg[it.key] ?: emptyList() }.maxByOrNull { tv.sloopy.core.model.Dupes.quality(it.name) }
                if (fromGuide != null) { gameChannelCache[g.id] = Triple(fromGuide.id, "guide", now); return@map GameRow(g, dupes.value.byChannel[fromGuide.id]?.primary ?: fromGuide, "guide") }
                // A channel named after both teams, anywhere in the lineup, beats any network guess.
                val byName = live.filter { tv.sloopy.core.sports.Scores.score(nameNorm[it.id] ?: " ", g) == 2 }.maxByOrNull { tv.sloopy.core.model.Dupes.quality(it.name) }
                if (byName != null) { gameChannelCache[g.id] = Triple(byName.id, "name", now); return@map GameRow(g, byName, "name") }
                for (net in g.networks.take(3)) {
                    networkMap.value[net]?.let { id -> live.firstOrNull { it.id == id }?.let { gameChannelCache[g.id] = Triple(it.id, net, now); return@map GameRow(g, it, net) } }
                    val cands = forNetwork(net)
                    val guess = (if (cands.size > 1) byListing(g, cands) else null) ?: cands.firstOrNull()
                    if (guess != null) { gameChannelCache[g.id] = Triple(guess.id, "$net?", now); return@map GameRow(g, guess, "$net?") }
                }
                gameChannelCache[g.id] = Triple(null, "", now)
                GameRow(g, null, "")
            }
        }
    }
    /** A league's teams, kept a week on the stick. */
    suspend fun teamsFor(league: String): List<tv.sloopy.core.sports.Team> {
        prefs.teamList(league)?.let { (at, lines) -> if (System.currentTimeMillis() - at < 7 * 86_400_000L && lines.isNotEmpty()) return lines.mapNotNull { FollowedTeam.decode(it) }.map { tv.sloopy.core.sports.Team(it.league, it.id, it.name, it.abbr, it.logo) } }
        val fresh = runCatching { scores.teams(league) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) prefs.setTeamList(league, fresh.map { FollowedTeam.of(it).encode() })
        return fresh
    }
    private val standingsCache = HashMap<String, Pair<Long, List<tv.sloopy.core.sports.StandingsGroup>>>()
    suspend fun standingsFor(league: String): List<tv.sloopy.core.sports.StandingsGroup> {
        standingsCache[league]?.let { (at, v) -> if (System.currentTimeMillis() - at < 15 * 60_000L) return v }
        val v = runCatching { scores.standings(league) }.getOrDefault(emptyList())
        if (v.isNotEmpty()) standingsCache[league] = System.currentTimeMillis() to v
        return v
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
    /** A change is waiting for the gap to pass; switch-off flushes it (see [onCleared]). */
    @Volatile private var syncPending = false

    /**
     * Uploads this stick's bundle under its code. Quiet unless [announce]; automatic uploads wait
     * [SYNC_GAP_MS] between runs (every channel change touches History, and the code server's KV
     * has a thousand writes a day for everyone), and whatever changed meanwhile rides the next one.
     */
    fun syncNow(announce: Boolean = false) = viewModelScope.launch {
        val code = syncCode.value ?: return@launch
        if (syncing) { syncPending = true; return@launch }
        syncing = true
        try {
            if (!announce) { syncPending = true; val wait = SYNC_GAP_MS - (System.currentTimeMillis() - lastUploadAt); if (wait > 0) delay(wait) }
            uploadBundle(code)
            if (announce) _syncStatus.value = "Uploaded: ${syncManager.build().summary}."
        } catch (e: Exception) {
            _syncStatus.value = "Upload failed: ${TLog.userMessage(e)}"
            TLog.w { "sync upload: ${TLog.userMessage(e)}" }
        } finally { syncing = false }
    }

    private suspend fun uploadBundle(code: String) {
        syncPending = false
        lastUploadAt = System.currentTimeMillis()
        val at = tv.sloopy.sync.SyncClient.upload(codeServer.value, code, syncManager.encode(syncManager.build()))
        prefs.setSyncedAt(at)
    }
    fun enableSync() = viewModelScope.launch { prefs.setSyncCode(tv.sloopy.sync.SyncClient.newCode()); _syncStatus.value = null; syncNow(announce = true) }
    fun disableSync() = viewModelScope.launch { prefs.setSyncCode(null); prefs.setSyncedAt(0); _syncStatus.value = "Sync is off. The last backup stays on the server under the old code until it is replaced." }
    /** Pulls the bundle behind [code] onto this stick and joins that code. */
    suspend fun restoreFromCode(code: String): String = applyBundle(fetchBundle(code), code, tv.sloopy.sync.SyncManager.RestoreOptions())
    /** Downloads and decrypts the other stick's backup so the viewer can choose what to bring over. */
    suspend fun fetchBundle(code: String): tv.sloopy.sync.SyncBundle = syncManager.decode(tv.sloopy.sync.SyncClient.download(codeServer.value, code))
    /**
     * Applies what was chosen. Only a full copy (login included) joins the other stick's code; a
     * partial one keeps this stick's own code, or its own backup would overwrite the other stick's.
     */
    suspend fun applyBundle(bundle: tv.sloopy.sync.SyncBundle, code: String, opts: tv.sloopy.sync.SyncManager.RestoreOptions): String {
        val note = syncManager.apply(bundle, opts)
        if (opts.login) { prefs.setSyncCode(tv.sloopy.sync.SyncClient.normalise(code)); prefs.setSyncedAt(bundle.at) }
        active.value?.let { refresh(it) }
        return note.ifBlank { bundle.summary }
    }
    init {
        // Re-upload a few seconds after anything in the bundle changes. Every source is
        // distinctUntilChanged: the raw Prefs flows emit on any DataStore edit (including the
        // synced-at stamp an upload writes) and Room re-emits on any table write, which until
        // 0.6.13 made each upload schedule the next one a minute later, all evening (the flat
        // 60-writes-an-hour line on the KV chart, 2026-10-16).
        viewModelScope.launch {
            val progressTick = active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else graph.db.progress().recent(p.id, 1) }
            val sources: List<kotlinx.coroutines.flow.Flow<Any?>> = listOf(
                favouriteIds, favouriteVodIds, favouriteSeriesIds, groupOrder, lockedGroups, hiddenGroups, hiddenChannels, providerList, progressTick, prefs.collapseDupes, dupSplit, dupMerged, dupDismissed, dupPreferred,
                prefs.theme, prefs.clock12, prefs.frameRateMatch, prefs.introEnabled, prefs.osdSeconds, prefs.searchHistory, prefs.license,
            )
            val ticks: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.merge(*sources.map { it.distinctUntilChanged() }.toTypedArray()).map { }
            ticks.drop(sources.size).debounce(8_000).collect { if (syncCode.value != null) syncNow() }
        }
    }

    val introEnabled: StateFlow<Boolean> = prefs.introEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val bufferSize: StateFlow<String> = prefs.bufferSize.stateIn(viewModelScope, SharingStarted.Eagerly, "large")
    init { viewModelScope.launch { bufferSize.collect { player.setBuffer(it) } } }

    // ---- External player (VLC) ----------------------------------------------------------------
    val externalPlayer: StateFlow<String> = prefs.externalPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, "builtin")
    /** Intents the Activity starts: VLC opening a stream. */
    val externalLaunches = kotlinx.coroutines.flow.MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 2)
    fun vlcInstalled(): Boolean = runCatching { getApplication<android.app.Application>().packageManager.getLaunchIntentForPackage(VLC) != null }.getOrDefault(false)
    /** Hands [url] to VLC. False (with a toast) when VLC is not installed, so the caller plays it itself. */
    fun openInVlc(url: String, title: String): Boolean {
        if (!vlcInstalled()) { _toast.value = "VLC is not installed on this box"; return false }
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndTypeAndNormalize(android.net.Uri.parse(url), "video/*")
            .setPackage(VLC)
            .putExtra("title", title)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        player.player.pause()
        return externalLaunches.tryEmit(i)
    }
    private fun useVlc(): Boolean = externalPlayer.value == "vlc" && vlcInstalled()
    fun openChannelInVlc(c: ChannelEntity) { val p = active.value ?: return; runCatching { openInVlc(catalog.liveUrl(p, c), c.name) }.onFailure { _toast.value = TLog.userMessage(it) } }
    fun openVodInVlc(id: Long, container: String, kind: String, title: String) {
        val p = active.value ?: return
        openInVlc(if (kind == "episode") catalog.episodeUrl(p, id, container) else catalog.vodUrl(p, id, container), title)
    }
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
    val epgIntervalHours: StateFlow<Int> = prefs.epgIntervalHours.stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    init {
        // Re-download the guide on the chosen cadence while the app is open.
        viewModelScope.launch { epgIntervalHours.collectLatest { h -> while (true) { delay(h * 3600_000L); refreshEpgNow() } } }
    }
    fun refreshEpgNow() = viewModelScope.launch {
        val p = active.value ?: return@launch
        runCatching { catalog.refreshEpg(p, minIntervalMs = epgIntervalHours.value * 3600_000L) }
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
    /**
     * Guide rows for one channel: cache, then the database, then (Xtream only, at most hourly per
     * channel) the panel's own per-channel table for providers whose xmltv.php is refused.
     */
    suspend fun programmesFor(channel: ChannelEntity, winStart: Long): List<tv.sloopy.data.db.ProgrammeEntity> {
        val key = tv.sloopy.data.channelEpgKey(channel)
        cachedProgrammes(key, winStart)?.takeIf { it.isNotEmpty() }?.let { return it }
        val p = active.value ?: return emptyList()
        val winEnd = winStart + (GUIDE_HOURS_BACK + GUIDE_HOURS_FWD) * 3600
        val rows = runCatching { catalog.programmes(p.id, key, winStart, winEnd) }.getOrDefault(emptyList())
        epgCache[epgKey(key, winStart)] = rows
        return rows
    }

    // ---- The guide store: the rows near the view, in memory, a few queries at most ------------
    // A guide row never queries the database or the network itself. The screen asks for the
    // channels around the view (about 130 rows) across a band of hours around it; the store
    // answers from memory when it has them and runs one query for what is missing. Scrolling
    // down loads the next rows, scrolling sideways widens the band. Moving to another group
    // cancels a load still in flight, so flicking through fifty groups costs one small query:
    // the group you stop on. The last few groups stay in memory so bouncing back is instant.
    class GroupGuide(val key: String, val lo: Long, val hi: Long, val rows: Map<String, List<tv.sloopy.data.db.ProgrammeEntity>>) {
        fun covers(lo: Long, hi: Long) = this.lo <= lo && this.hi >= hi
    }
    private val guides = object : LinkedHashMap<String, GroupGuide>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroupGuide>?) = size > 6
    }
    private val _groupGuide = MutableStateFlow<GroupGuide?>(null)
    val groupGuide: StateFlow<GroupGuide?> = _groupGuide
    // One loader per group. Requests (the screen's rows, the warm-up's rows, a wider band) are
    // merged into a queue the loader drains nearest-first; they never cancel each other. Only a
    // different group cancels, which is what makes flicking through groups cheap.
    private var guideJob: Job? = null
    private var loadingGroup: String? = null
    private val guideQueue = LinkedHashSet<String>()
    private var bandLo = 0L; private var bandHi = 0L
    @Volatile var guideLoading = false; private set
    /** The channels the guide last asked for, so the sweep can fill what is on screen first. */
    @Volatile private var guideWanted: List<ChannelEntity> = emptyList()
    fun ensureGuide(groupKey: String, list: List<ChannelEntity>, rowLo: Int, rowHi: Int, lo: Long, hi: Long, near: Int = rowLo + 40) {
        if (list.isEmpty()) return
        val a = rowLo.coerceIn(0, list.size); val b = rowHi.coerceIn(0, list.size)
        // Nearest the view first, so the sweep fills the rows on screen before the ones below.
        val slice = (a until b).sortedBy { kotlin.math.abs(it - near) }.map { list[it] }
        val wantList = (slice + list.take(40)).distinctBy { it.id }
        guideWanted = wantList
        val want = wantList.map { tv.sloopy.data.channelEpgKey(it) }.distinct()
        synchronized(guideQueue) {
            val cur = guides[groupKey]
            val sameGroup = loadingGroup == groupKey
            val widen = cur != null && !cur.covers(lo, hi)
            if (cur != null && !widen && cur.rows.keys.containsAll(want)) {
                if (_groupGuide.value !== cur) _groupGuide.value = cur
                if (!sameGroup) return
                if (guideQueue.isEmpty()) return
            }
            if (!sameGroup) {
                guideJob?.cancel(); guideQueue.clear(); loadingGroup = groupKey
                bandLo = if (cur != null) minOf(lo, cur.lo) else lo; bandHi = if (cur != null) maxOf(hi, cur.hi) else hi
                // Another group: placeholders until its rows arrive. Same group: keep what it has.
                _groupGuide.value = if (cur != null && !widen) cur else if (_groupGuide.value?.key == groupKey) _groupGuide.value else null
                if (widen && cur != null) { guideQueue.addAll(cur.rows.keys.take(400)); guides[groupKey] = GroupGuide(groupKey, bandLo, bandHi, emptyMap()) }
                guideQueue.addAll(want)
            } else {
                if (widen && cur != null) {
                    bandLo = minOf(bandLo, lo); bandHi = maxOf(bandHi, hi)
                    guideQueue.addAll(cur.rows.keys.take(400)); guides[groupKey] = GroupGuide(groupKey, bandLo, bandHi, emptyMap())
                }
                val have = guides[groupKey]?.rows?.keys ?: emptySet()
                guideQueue.addAll(want.filter { it !in have })
            }
            if (guideJob?.isActive != true) guideJob = viewModelScope.launch { drainGuideQueue(groupKey) }
        }
    }
    private val _guideStats = MutableStateFlow("")
    /** For Settings › About: how long the last guide reads took. */
    val guideStats: StateFlow<String> = _guideStats
    private suspend fun drainGuideQueue(groupKey: String) {
        val p = active.value ?: return
        guideLoading = true
        val t0 = System.currentTimeMillis(); var rowsRead = 0; var reads = 0
        try {
            while (true) {
                val chunk = synchronized(guideQueue) {
                    val have = guides[groupKey]?.rows?.keys ?: emptySet()
                    guideQueue.removeAll(have)
                    val c = guideQueue.take(200); guideQueue.removeAll(c.toSet()); c
                }
                if (chunk.isEmpty()) break
                val lo = bandLo; val hi = bandHi
                val got = runCatching { catalog.programmesManyDirect(p.id, chunk, lo, hi) }.getOrDefault(emptyList()).groupBy { it.epgId }
                rowsRead += chunk.size; reads++
                synchronized(guideQueue) {
                    val cur = guides[groupKey]
                    val rows = HashMap(cur?.rows ?: emptyMap())
                    chunk.forEach { rows[it] = got[it] ?: emptyList() }
                    val g = GroupGuide(groupKey, lo, hi, rows)
                    guides[groupKey] = g
                    _groupGuide.value = g
                }
                kotlinx.coroutines.yield()
            }
        } finally {
            guideLoading = false
            if (reads > 0) {
                val ms = System.currentTimeMillis() - t0
                val total = runCatching { catalog.programmeCount(p.id) }.getOrDefault(-1)
                val wal = catalog.walBytes(getApplication()) / 1_000_000
                val dbMb = runCatching { getApplication<android.app.Application>().getDatabasePath("sloopy.db").length() / 1_000_000 }.getOrDefault(0L)
                _guideStats.value = "Last guide read: $rowsRead rows in $ms ms · ${"%,d".format(total)} programmes in the table · database $dbMb MB · log file $wal MB"
            }
        }
    }
    // Channels the sweep filled are folded into the store a few at a time, not one rebuild each.
    private val changedKeys = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private suspend fun flushChangedKeys() {
        val keys = synchronized(changedKeys) { val k = changedKeys.toList(); changedKeys.clear(); k }
        if (keys.isEmpty()) return
        val p = active.value ?: return
        synchronized(guideQueue) { guides.entries.removeAll { e -> e.value !== _groupGuide.value && keys.any { it in e.value.rows } } }
        val g = _groupGuide.value ?: return
        val mine = keys.filter { it in g.rows }
        if (mine.isEmpty()) return
        val got = runCatching { catalog.programmesManyDirect(p.id, mine, g.lo, g.hi) }.getOrDefault(emptyList()).groupBy { it.epgId }
        synchronized(guideQueue) {
            val now = guides[g.key] ?: return
            val rows = HashMap(now.rows); mine.forEach { rows[it] = got[it] ?: emptyList() }
            val ng = GroupGuide(now.key, now.lo, now.hi, rows)
            guides[g.key] = ng
            if (_groupGuide.value?.key == g.key) _groupGuide.value = ng
        }
    }
    private fun dropGuides() {
        val g = _groupGuide.value
        synchronized(guideQueue) { guides.clear(); guideJob?.cancel(); guideQueue.clear(); loadingGroup = null }
        if (g == null) return
        _groupGuide.value = null
        ensureGuide(g.key, visibleChannels.value, 0, 130, g.lo, g.hi)
    }

    // ---- The sweep: fills listings the guide file did not carry, in the background -------------
    // Xtream panels keep a per-channel table (get_simple_data_table). When the guide file is
    // refused (that HTTP 451) every channel needs it; otherwise only the odd channel the file
    // missed. What matters goes first and three at a time: the current channel, favourites, the
    // rows on screen. The rest trickles one at a time with a pause, so the panel is not hammered
    // and the database stays free for the guide. Never from a guide row.
    private val sweptAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile private var wideSweep = false
    /** True while this channel's listings are still to be fetched from the panel: the row says "Loading" rather than "No guide data". */
    fun listingsPending(c: ChannelEntity): Boolean = wideSweep && !sweptAt.containsKey(c.id)
    private val sweepKick = MutableStateFlow(0)
    private var sweepJob: Job? = null
    private val _sweepProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (done, total) while listings are still being filled in; null when the sweep is idle. */
    val sweepProgress: StateFlow<Pair<Int, Int>?> = _sweepProgress
    fun kickSweep() { sweepKick.value++ }
    private fun startSweep() {
        sweepJob?.cancel()
        sweepJob = viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            val gate2 = kotlinx.coroutines.sync.Semaphore(2)
            var done = 0; var total = 0
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val p = active.value
                if (p == null || p.kind != tv.sloopy.data.ProviderKind.XTREAM) { _sweepProgress.value = null; val k = sweepKick.value; sweepKick.first { it != k }; continue }
                val wide = _epgError.value != null || runCatching { catalog.programmeCount(p.id) }.getOrDefault(1) == 0
                wideSweep = wide
                val now = System.currentTimeMillis()
                val favs = favouriteIds.value; val hiddenG = hiddenGroups.value
                val fresh = { c: ChannelEntity -> c.categoryId !in hiddenG && now - (sweptAt[c.id] ?: 0L) > 6 * 3600_000L }
                // The rows on screen come before favourites: the viewer is looking at them now.
                val urgent = buildList {
                    currentChannel.value?.let { add(it) }
                    addAll(guideWanted)
                    addAll(channels.value.filter { it.id in favs })
                }.distinctBy { it.id }.filter(fresh)
                val rest = if (wide) channels.value.filter(fresh).filter { c -> urgent.none { it.id == c.id } } else emptyList()
                val remaining = urgent.size + rest.size
                if (remaining == 0) {
                    if (done > 0) catalog.checkpoint()
                    _sweepProgress.value = null; done = 0; total = 0; val k = sweepKick.value; kotlinx.coroutines.withTimeoutOrNull(600_000) { sweepKick.first { it != k } }; continue
                }
                total = maxOf(total, remaining + done)
                _sweepProgress.value = done to total
                val batch = if (urgent.isNotEmpty()) urgent.take(if (wide) 18 else 4) else rest.take(1)
                val gentle = urgent.isEmpty()
                // The guide is reading: let it finish before the next round of writes.
                var waited = 0; while (guideLoading && waited < 40) { delay(50); waited++ }
                val nowS = now / 1000
                batch.map { c ->
                    launch {
                        (if (wide) gate else gate2).withPermit {
                            val key = tv.sloopy.data.channelEpgKey(c)
                            val have = runCatching { catalog.programmes(p.id, key, nowS - 6 * 3600, nowS + 6 * 3600) }.getOrDefault(emptyList())
                            if (have.isNotEmpty()) { sweptAt[c.id] = now; return@withPermit }
                            // A channel the panel had nothing for is remembered for a day, so every launch
                            // does not spend its first seconds re-asking about the same empty ones.
                            val emptyAt = runCatching { graph.db.meta().get(p.id, "nolist:${c.id}")?.toLongOrNull() }.getOrNull() ?: 0L
                            if (now - emptyAt < 24 * 3600_000L) { sweptAt[c.id] = now; changedKeys.add(key); return@withPermit }
                            val n = try { catalog.fetchChannelEpg(p, c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { TLog.w { "sweep: ${TLog.userMessage(e)}" }; -1 }
                            sweptAt[c.id] = if (n < 0) now - 6 * 3600_000L + 900_000L else now   // refused: try again in fifteen minutes
                            if (n == 0) runCatching { graph.db.meta().set(tv.sloopy.data.db.MetaEntity(p.id, "nolist:${c.id}", now.toString())) }
                            if (n >= 0) { epgCache.keys.removeAll { it.endsWith("|$key") }; changedKeys.add(key) }
                        }
                    }
                }.forEach { it.join() }
                done += batch.size
                if (gentle) delay(400)
            }
        }
    }
    init {
        startSweep()
        viewModelScope.launch { while (true) { delay(700); runCatching { flushChangedKeys() } } }
        // Warm the store for the group on screen while the picture plays, so Back draws a full
        // grid on its first frame instead of placeholders.
        viewModelScope.launch {
            combine(visibleChannels, _group) { l, g -> g to l }.collect { (g, l) ->
                if (l.isNotEmpty()) {
                    val ws = guideWindowStart(); val now = System.currentTimeMillis() / 1000 / 3600 * 3600
                    val i = l.indexOfFirst { it.id == currentChannel.value?.id }.coerceAtLeast(0)
                    ensureGuide(g, l, i - 40, i + 90, maxOf(ws, now - GUIDE_BAND_BACK * 3600), now + GUIDE_BAND_FWD * 3600, near = i)
                }
            }
        }
        viewModelScope.launch { combine(currentChannel, visibleChannels, favouriteIds) { c, l, f -> Triple(c?.id, l.size, f.size) }.collect { kickSweep() } }
        viewModelScope.launch { catalog.refresh.collect { if (it is tv.sloopy.data.RefreshState.Done) { epgCache.clear(); sweptAt.clear(); dropGuides(); kickSweep() } } }
        viewModelScope.launch { _epgError.collect { kickSweep() } }
    }

    fun forgetProgress(r: tv.sloopy.data.db.ProgressEntity) = viewModelScope.launch { graph.db.progress().remove(r.providerId, r.kind, r.itemId) }

    fun tune(c: ChannelEntity) {
        val p = active.value ?: return
        viewModelScope.launch { prefs.setLastChannel(p.id, c.id); prefs.pushRecent(p.id, c.id); prefs.bumpWatch(p.id, c.id) }
        if (useVlc() && runCatching { openInVlc(catalog.liveUrl(p, c), c.name) }.getOrDefault(false)) return
        _playing.value = Playing.Live(c)
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

    private val _lastSeek = MutableStateFlow<Long?>(null)
    val lastSeek: StateFlow<Long?> = _lastSeek
    private var seekClear: kotlinx.coroutines.Job? = null
    /** Accumulates quick presses ("+90 s") and clears two seconds after the last one. */
    fun noteSeek(ms: Long) {
        _lastSeek.value = (if ((_lastSeek.value ?: 0L) * ms > 0) _lastSeek.value!! else 0L) + ms
        seekClear?.cancel(); seekClear = viewModelScope.launch { delay(2000); _lastSeek.value = null }
    }
    fun seekBy(ms: Long) { val p = player.player; p.seekTo((p.currentPosition + ms).coerceIn(0, maxOf(0, p.duration))) }

    fun playCatchup(c: ChannelEntity, title: String, startEpoch: Long, stopEpoch: Long) {
        val p = active.value ?: return
        val url = catalog.catchupUrl(p, c, startEpoch, ((stopEpoch - startEpoch) / 60).toInt().coerceAtLeast(1), ZoneId.systemDefault())
        if (url == null) { _toast.value = "No catch-up for this channel"; return }
        if (useVlc() && openInVlc(url, title)) return
        _playing.value = Playing.Catchup(c, title, startEpoch, stopEpoch)
        player.play(url, isLive = false)
    }

    /** True when the built-in player took it (open the player screen); false when VLC did. */
    fun playVod(id: Long, title: String, subtitle: String?, image: String?, container: String, kind: String, parentId: Long?, resumeMs: Long): Boolean {
        val p = active.value ?: return false
        if (kind == "recording") { viewModelScope.launch { graph.db.recordings().get(id)?.let { playRecording(it) } }; return true }
        val url = if (kind == "episode") catalog.episodeUrl(p, id, container) else catalog.vodUrl(p, id, container)
        if (useVlc() && openInVlc(url, title)) return false
        _playing.value = Playing.Vod(id, title, subtitle, image, container, kind, parentId)
        player.play(url, isLive = false, startPositionMs = resumeMs)
        return true
    }

    /** Called when leaving the VOD player to remember position. */
    // Written on a scope of its own: the activity calls this on its way out, when viewModelScope is
    // about to be cancelled, and a resume point that never lands is the bug nobody can reproduce.
    private val detachedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun saveProgress() {
        val p = active.value ?: return
        val v = _playing.value as? Playing.Vod ?: return
        val pos = player.position()
        val dur = player.duration()
        if (dur <= 0) return
        detachedScope.launch {
        if (pos > dur - 60_000 || pos < 15_000) {
            if (pos > dur - 60_000) graph.db.progress().remove(p.id, v.kind, v.id)
            return@launch
        }
        graph.db.progress().upsert(
            tv.sloopy.data.db.ProgressEntity(p.id, v.kind, v.id, v.parentId, pos, dur, System.currentTimeMillis(), v.title, v.subtitle, v.image, v.container)
        )
        }
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
        runCatching { remote?.stop() }
        player.release()
        // A change still waiting for the upload gap goes now, so switch-off never loses it.
        val code = syncCode.value
        if (syncPending && code != null) detachedScope.launch { runCatching { uploadBundle(code) }.onFailure { TLog.w { "sync flush: ${TLog.userMessage(it)}" } } }
    }

    companion object {
        /** Automatic backup uploads at most this often; a pending one is flushed at switch-off. */
        private const val SYNC_GAP_MS = 15 * 60_000L
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
