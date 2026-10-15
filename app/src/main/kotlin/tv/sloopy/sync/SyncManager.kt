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
    val channelSorts: Map<String, String>,
    val lastChannel: Map<String, Long>,
    val lastGroup: Map<String, String>,
    val recents: Map<String, List<Long>>,
    val pinSalt: String?, val pinHash: String?,
    val theme: String, val clock12: Boolean, val frameRateMatch: Boolean, val intro: Boolean, val osdSeconds: Int,
    val codeServer: String?, val searchHistory: List<String>, val license: String?,
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
            channelSorts = prefs.allChannelSorts(),
            lastChannel = ids.mapNotNull { p -> prefs.lastChannel(p)?.let { p to it } }.toMap(),
            lastGroup = ids.mapNotNull { p -> prefs.lastGroup(p)?.let { p to it } }.toMap(),
            recents = ids.associateWith { prefs.recentChannels(it).first() },
            pinSalt = prefs.pinSalt.first(), pinHash = prefs.pinHash.first(),
            theme = prefs.theme.first(), clock12 = prefs.clock12.first(), frameRateMatch = prefs.frameRateMatch.first(),
            intro = prefs.introEnabled.first(), osdSeconds = prefs.osdSeconds.first(),
            codeServer = prefs.codeServer.first(), searchHistory = prefs.searchHistory.first(), license = prefs.license.first(),
        )
    }

    fun encode(b: SyncBundle): ByteArray = json.encodeToString(SyncBundle.serializer(), b).toByteArray()
    fun decode(bytes: ByteArray): SyncBundle = json.decodeFromString(SyncBundle.serializer(), bytes.decodeToString())

    /** Lays the bundle over this device. Existing rows with the same keys are replaced, nothing else is removed. */
    suspend fun apply(b: SyncBundle) {
        b.providers.forEach { providers.upsert(it) }
        b.favourites.forEach { (p, ids) -> ids.forEach { db.favourites().add(FavouriteEntity(p, it, System.currentTimeMillis())) } }
        b.favouriteItems.forEach { (p, items) -> items.forEach { db.favouriteItems().add(FavouriteItemEntity(p, it.kind, it.id, System.currentTimeMillis())) } }
        b.progress.forEach { r ->
            val mine = db.progress().get(r.providerId, r.kind, r.itemId)
            if (mine == null || mine.updatedAt < r.updatedAt) db.progress().upsert(ProgressEntity(r.providerId, r.kind, r.itemId, r.parentId, r.positionMs, r.durationMs, r.updatedAt, r.title, r.subtitle, r.image, r.container))
        }
        b.groupOrder.forEach { (p, o) -> if (o.isNotEmpty()) prefs.setGroupOrder(p, o) }
        b.lockedGroups.forEach { (p, l) -> prefs.setLockedGroups(p, l.toSet()) }
        b.hiddenGroups.forEach { (p, l) -> prefs.setHiddenGroups(p, l.toSet()) }
        prefs.restoreChannelSorts(b.channelSorts)
        b.lastChannel.forEach { (p, c) -> prefs.setLastChannel(p, c) }
        b.lastGroup.forEach { (p, g) -> prefs.setLastGroup(p, g) }
        b.recents.forEach { (p, l) -> l.reversed().forEach { prefs.pushRecent(p, it) } }
        if (b.pinHash != null) prefs.setPin(b.pinSalt, b.pinHash)
        prefs.setTheme(b.theme); prefs.setClock12(b.clock12); prefs.setFrameRateMatch(b.frameRateMatch)
        prefs.setIntroEnabled(b.intro); prefs.setOsdSeconds(b.osdSeconds)
        if (!b.codeServer.isNullOrBlank()) prefs.setCodeServer(b.codeServer)
        b.searchHistory.reversed().forEach { prefs.pushSearch(it) }
        if (!b.license.isNullOrBlank()) prefs.setLicense(b.license)
        (b.activeProvider ?: b.providers.firstOrNull()?.id)?.let { prefs.setActiveProvider(it) }
    }
}
