package tv.sloopy.sync

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.sloopy.data.Prefs
import tv.sloopy.data.ProviderRecord
import tv.sloopy.data.ProviderStore
import tv.sloopy.data.db.FavouriteEntity
import tv.sloopy.data.db.FavouriteItemEntity
import tv.sloopy.data.db.ProgressEntity
import tv.sloopy.data.db.SloopyDb

@Serializable data class FavItem(val kind: String, val id: Long)
@Serializable data class ProgressRow(val providerId: String, val kind: String, val itemId: Long, val parentId: Long?, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val title: String, val subtitle: String?, val image: String?, val container: String)

/** Everything a viewer chose, so another stick can be made identical. Cached data stays out. */
@Serializable
data class SyncBundle(
    val v: Int = 1,
    val at: Long,
    val providers: List<ProviderRecord>,
    val activeProvider: String?,
    val favourites: Map<String, List<Long>>,
    val favouriteItems: Map<String, List<FavItem>>,
    val progress: List<ProgressRow>,
    val groupOrder: Map<String, List<String>>,
    val lockedGroups: Map<String, List<String>>,
    val hiddenGroups: Map<String, List<String>> = emptyMap(),
    val hiddenChannels: Map<String, List<Long>> = emptyMap(),
    val dupSplit: Map<String, List<String>> = emptyMap(),
    val dupMerged: Map<String, List<String>> = emptyMap(),
    val dupDismissed: Map<String, List<String>> = emptyMap(),
    val dupPreferred: Map<String, Map<String, Long>> = emptyMap(),
    val collapseDupes: Boolean = true,
    val channelSorts: Map<String, String>,
    val lastChannel: Map<String, Long>,
    val lastGroup: Map<String, String>,
    val recents: Map<String, List<Long>>,
    val pinSalt: String?, val pinHash: String?,
    val theme: String, val clock12: Boolean, val frameRateMatch: Boolean, val intro: Boolean, val osdSeconds: Int,
    val codeServer: String?, val searchHistory: List<String>, val license: String?,
    val followedTeams: List<String> = emptyList(), val hideScores: Boolean = false,
    val friendsLicense: String? = null,
) {
    val summary: String get() = "${providers.size} provider${if (providers.size == 1) "" else "s"}, ${favourites.values.sumOf { it.size }} favourite channels, ${favouriteItems.values.sumOf { it.size }} in My list, ${progress.size} in History"
}

class SyncManager(private val db: SloopyDb, private val prefs: Prefs, private val providers: ProviderStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun build(): SyncBundle {
        val list = providers.providers.value
        val ids = list.map { it.id }
        return SyncBundle(
            at = System.currentTimeMillis(),
            providers = list,
            activeProvider = prefs.activeProviderId.first(),
            favourites = ids.associateWith { db.favourites().ids(it).first() },
            favouriteItems = ids.associateWith { p -> listOf("vod", "series").flatMap { k -> db.favouriteItems().ids(p, k).first().map { FavItem(k, it) } } },
            progress = ids.flatMap { p -> db.progress().recent(p, 500).first() }.map { ProgressRow(it.providerId, it.kind, it.itemId, it.parentId, it.positionMs, it.durationMs, it.updatedAt, it.title, it.subtitle, it.image, it.container) },
            groupOrder = ids.associateWith { prefs.groupOrder(it).first() },
            lockedGroups = ids.associateWith { prefs.lockedGroups(it).first().toList() },
            hiddenGroups = ids.associateWith { prefs.hiddenGroups(it).first().toList() },
            hiddenChannels = ids.associateWith { prefs.hiddenChannels(it).first().toList() },
            dupSplit = ids.associateWith { prefs.dupSplit(it).first().toList() },
            dupMerged = ids.associateWith { prefs.dupMerged(it).first().toList() },
            dupDismissed = ids.associateWith { prefs.dupDismissed(it).first().toList() },
            dupPreferred = ids.associateWith { prefs.dupPreferred(it).first() },
            collapseDupes = prefs.collapseDupes.first(),
            channelSorts = prefs.allChannelSorts(),
            lastChannel = ids.mapNotNull { p -> prefs.lastChannel(p)?.let { p to it } }.toMap(),
            lastGroup = ids.mapNotNull { p -> prefs.lastGroup(p)?.let { p to it } }.toMap(),
            recents = ids.associateWith { prefs.recentChannels(it).first() },
            pinSalt = prefs.pinSalt.first(), pinHash = prefs.pinHash.first(),
            theme = prefs.theme.first(), clock12 = prefs.clock12.first(), frameRateMatch = prefs.frameRateMatch.first(),
            intro = prefs.introEnabled.first(), osdSeconds = prefs.osdSeconds.first(),
            followedTeams = prefs.followedTeams.first(), hideScores = prefs.hideScores.first(), friendsLicense = prefs.friendsLicense.first(),
            codeServer = prefs.codeServer.first(), searchHistory = prefs.searchHistory.first(), license = prefs.license.first(),
        )
    }

    fun encode(b: SyncBundle): ByteArray = json.encodeToString(SyncBundle.serializer(), b).toByteArray()
    fun decode(bytes: ByteArray): SyncBundle = json.decodeFromString(SyncBundle.serializer(), bytes.decodeToString())

    /** What a restore brings over. With [login] off, the other stick's choices are laid over this stick's own provider. */
    data class RestoreOptions(val login: Boolean = true, val favourites: Boolean = true, val groups: Boolean = true, val history: Boolean = true, val settings: Boolean = true)

    /**
     * Lays the bundle over this device. Existing rows with the same keys are replaced, nothing else
     * is removed. Without the login, every per-provider piece is re-keyed from the bundle's active
     * provider to this stick's, and favourites that do not exist on this login are skipped (same
     * panel, same ids; a different service cannot be mapped). Returns a short note of what landed.
     */
    suspend fun apply(b: SyncBundle, o: RestoreOptions = RestoreOptions()): String {
        val src = b.activeProvider ?: b.providers.firstOrNull()?.id
        val target: String? = if (o.login) null else prefs.activeProviderId.first()
        if (!o.login && target == null) throw IllegalStateException("Add a provider on this stick first, then bring the other stick's choices over.")
        fun <T> per(m: Map<String, T>): Map<String, T> = if (target == null) m else (src?.let { m[it] }?.let { mapOf(target to it) } ?: emptyMap())
        val notes = ArrayList<String>()
        if (o.login) b.providers.forEach { providers.upsert(it) }
        if (o.favourites) {
            var n = 0; var skipped = 0
            per(b.favourites).forEach { (p, ids) -> ids.forEach { if (target == null || db.channels().byId(p, it) != null) { db.favourites().add(FavouriteEntity(p, it, System.currentTimeMillis())); n++ } else skipped++ } }
            per(b.favouriteItems).forEach { (p, items) -> items.forEach { db.favouriteItems().add(FavouriteItemEntity(p, it.kind, it.id, System.currentTimeMillis())); n++ } }
            notes.add("$n favourites" + (if (skipped > 0) " ($skipped not on this login)" else ""))
        }
        if (o.history) {
            var n = 0
            b.progress.filter { target == null || it.providerId == src }.forEach { r ->
                val pid = target ?: r.providerId
                val mine = db.progress().get(pid, r.kind, r.itemId)
                if (mine == null || mine.updatedAt < r.updatedAt) { db.progress().upsert(ProgressEntity(pid, r.kind, r.itemId, r.parentId, r.positionMs, r.durationMs, r.updatedAt, r.title, r.subtitle, r.image, r.container)); n++ }
            }
            per(b.recents).forEach { (p, l) -> l.reversed().forEach { prefs.pushRecent(p, it) } }
            notes.add("$n in History")
        }
        if (o.groups) {
            per(b.groupOrder).forEach { (p, l) -> if (l.isNotEmpty()) prefs.setGroupOrder(p, l) }
            per(b.lockedGroups).forEach { (p, l) -> prefs.setLockedGroups(p, l.toSet()) }
            per(b.hiddenGroups).forEach { (p, l) -> prefs.setHiddenGroups(p, l.toSet()) }
            per(b.hiddenChannels).forEach { (p, l) -> prefs.setHiddenChannels(p, l.toSet()) }
            per(b.dupSplit).forEach { (p, l) -> prefs.setDupSplit(p, l.toSet()) }
            per(b.dupMerged).forEach { (p, l) -> prefs.setDupMerged(p, l.toSet()) }
            per(b.dupDismissed).forEach { (p, l) -> prefs.setDupDismissed(p, l.toSet()) }
            per(b.dupPreferred).forEach { (p, m) -> prefs.setDupPreferred(p, m) }
            per(b.lastGroup).forEach { (p, g) -> prefs.setLastGroup(p, g) }
            if (target == null) prefs.restoreChannelSorts(b.channelSorts)
            notes.add("group order and hidden groups")
        }
        if (o.settings) {
            prefs.setCollapseDupes(b.collapseDupes)
            if (b.pinHash != null) prefs.setPin(b.pinSalt, b.pinHash)
            prefs.setTheme(b.theme); prefs.setClock12(b.clock12); prefs.setFrameRateMatch(b.frameRateMatch)
            prefs.setIntroEnabled(b.intro); prefs.setOsdSeconds(b.osdSeconds)
            if (!b.codeServer.isNullOrBlank()) prefs.setCodeServer(b.codeServer)
            b.searchHistory.reversed().forEach { prefs.pushSearch(it) }
            if (!b.license.isNullOrBlank()) prefs.setLicense(b.license)
            if (!b.friendsLicense.isNullOrBlank()) prefs.setFriendsLicense(b.friendsLicense)
            if (b.followedTeams.isNotEmpty()) prefs.setFollowedTeams((prefs.followedTeams.first() + b.followedTeams).distinct())
            prefs.setHideScores(b.hideScores)
            notes.add("settings")
        }
        if (o.login) {
            b.lastChannel.forEach { (p, c) -> prefs.setLastChannel(p, c) }
            (b.activeProvider ?: b.providers.firstOrNull()?.id)?.let { prefs.setActiveProvider(it) }
            notes.add(0, "${b.providers.size} provider login${if (b.providers.size == 1) "" else "s"}")
        }
        return notes.joinToString(", ")
    }
}
