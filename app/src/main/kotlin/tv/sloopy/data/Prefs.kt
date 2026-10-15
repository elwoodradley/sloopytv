package tv.sloopy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "sloopy_prefs")

/** Non-secret settings. Secrets never go here; see [Vault]. */
class Prefs(private val context: Context) {
    private val ds get() = context.store

    private val activeProvider = stringPreferencesKey("active_provider")
    private val introSeen = booleanPreferencesKey("intro_seen")
    private val guideHoursBack = intPreferencesKey("guide_hours_back")
    private val epgRefreshedAt = longPreferencesKey("epg_refreshed_at")

    val activeProviderId: Flow<String?> = ds.data.map { it[activeProvider] }
    suspend fun setActiveProvider(id: String?) = ds.edit { if (id == null) it.remove(activeProvider) else it[activeProvider] = id }

    private fun lastChannelKey(providerId: String) = longPreferencesKey("last_channel_$providerId")
    suspend fun lastChannel(providerId: String): Long? = ds.data.first()[lastChannelKey(providerId)]
    suspend fun setLastChannel(providerId: String, channelId: Long) = ds.edit { it[lastChannelKey(providerId)] = channelId }

    private fun lastGroupKey(providerId: String) = stringPreferencesKey("last_group_$providerId")
    suspend fun lastGroup(providerId: String): String? = ds.data.first()[lastGroupKey(providerId)]
    suspend fun setLastGroup(providerId: String, group: String?) = ds.edit { if (group == null) it.remove(lastGroupKey(providerId)) else it[lastGroupKey(providerId)] = group }

    private fun refreshKey(providerId: String, what: String) = longPreferencesKey("refreshed_${what}_$providerId")
    suspend fun refreshedAt(providerId: String, what: String): Long = ds.data.first()[refreshKey(providerId, what)] ?: 0L
    suspend fun setRefreshedAt(providerId: String, what: String, at: Long = System.currentTimeMillis()) = ds.edit { it[refreshKey(providerId, what)] = at }

    val introEnabled: Flow<Boolean> = ds.data.map { it[booleanPreferencesKey("intro_enabled")] ?: true }
    suspend fun setIntroEnabled(v: Boolean) = ds.edit { it[booleanPreferencesKey("intro_enabled")] = v }

    val osdSeconds: Flow<Int> = ds.data.map { it[intPreferencesKey("osd_seconds")] ?: 4 }

    /** "small" | "medium" | "large": how much stream the player keeps ahead. */
    val bufferSize: Flow<String> = ds.data.map { it[stringPreferencesKey("buffer_size")] ?: "large" }
    suspend fun setBufferSize(v: String) = ds.edit { it[stringPreferencesKey("buffer_size")] = v }

    /** Hours between guide downloads. */
    val epgIntervalHours: Flow<Int> = ds.data.map { it[intPreferencesKey("epg_interval_h")] ?: 3 }
    suspend fun setEpgIntervalHours(v: Int) = ds.edit { it[intPreferencesKey("epg_interval_h")] = v }

    /** "builtin" | "vlc": who plays streams. */
    val externalPlayer: Flow<String> = ds.data.map { it[stringPreferencesKey("player")] ?: "builtin" }
    suspend fun setExternalPlayer(v: String) = ds.edit { it[stringPreferencesKey("player")] = v }

    val clock12: Flow<Boolean> = ds.data.map { it[booleanPreferencesKey("clock_12h")] ?: false }
    suspend fun setClock12(v: Boolean) = ds.edit { it[booleanPreferencesKey("clock_12h")] = v }

    val theme: Flow<String> = ds.data.map { it[stringPreferencesKey("theme")] ?: "phosphor" }
    suspend fun setTheme(v: String) = ds.edit { it[stringPreferencesKey("theme")] = v }

    /** Ask the display for a refresh rate that matches the stream (50 Hz for European channels). */
    val frameRateMatch: Flow<Boolean> = ds.data.map { it[booleanPreferencesKey("frame_rate_match")] ?: false }
    suspend fun setFrameRateMatch(v: Boolean) = ds.edit { it[booleanPreferencesKey("frame_rate_match")] = v }

    /** "provider" | "number" | "name", per group. */
    private fun sortKey(providerId: String, group: String) = stringPreferencesKey("channel_sort_${providerId}_$group")
    fun channelSort(providerId: String, group: String): Flow<String> = ds.data.map { it[sortKey(providerId, group)] ?: "provider" }
    suspend fun setChannelSort(providerId: String, group: String, v: String) = ds.edit { it[sortKey(providerId, group)] = v }

    /** The transfer code this stick syncs under, if sync is on. */
    val syncCode: Flow<String?> = ds.data.map { it[stringPreferencesKey("sync_code")] }
    suspend fun setSyncCode(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(stringPreferencesKey("sync_code")) else it[stringPreferencesKey("sync_code")] = v }
    val syncedAt: Flow<Long> = ds.data.map { it[longPreferencesKey("synced_at")] ?: 0L }
    suspend fun setSyncedAt(v: Long) = ds.edit { it[longPreferencesKey("synced_at")] = v }

    /** A document-tree URI the viewer picked for recordings; null means the app's own storage. */
    val recordFolder: Flow<String?> = ds.data.map { it[stringPreferencesKey("record_folder")] }
    suspend fun setRecordFolder(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(stringPreferencesKey("record_folder")) else it[stringPreferencesKey("record_folder")] = v }

    val license: Flow<String?> = ds.data.map { it[stringPreferencesKey("license")] }
    suspend fun setLicense(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(stringPreferencesKey("license")) else it[stringPreferencesKey("license")] = v.trim() }
    val licenseCheckedAt: Flow<Long> = ds.data.map { it[longPreferencesKey("license_checked_at")] ?: 0L }
    suspend fun setLicenseCheckedAt(v: Long) = ds.edit { it[longPreferencesKey("license_checked_at")] = v }

    suspend fun allChannelSorts(): Map<String, String> = ds.data.first().asMap()
        .filterKeys { it.name.startsWith("channel_sort_") }.map { (k, v) -> k.name.removePrefix("channel_sort_") to v.toString() }.toMap()
    suspend fun restoreChannelSorts(m: Map<String, String>) = ds.edit { p -> m.forEach { (k, v) -> p[stringPreferencesKey("channel_sort_$k")] = v } }

    val searchHistory: Flow<List<String>> = ds.data.map { p -> p[stringPreferencesKey("search_history")]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList() }
    suspend fun pushSearch(q: String) = ds.edit { p ->
        val cur = p[stringPreferencesKey("search_history")]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()
        p[stringPreferencesKey("search_history")] = (listOf(q.trim()) + cur.filter { !it.equals(q.trim(), ignoreCase = true) }).take(10).joinToString("\u0001")
    }
    suspend fun clearSearchHistory() = ds.edit { it.remove(stringPreferencesKey("search_history")) }
    suspend fun setOsdSeconds(v: Int) = ds.edit { it[intPreferencesKey("osd_seconds")] = v }

    private fun orderKey(providerId: String) = stringPreferencesKey("group_order_$providerId")
    fun groupOrder(providerId: String): Flow<List<String>> = ds.data.map { p -> p[orderKey(providerId)]?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList() }
    suspend fun setGroupOrder(providerId: String, ids: List<String>) = ds.edit { it[orderKey(providerId)] = ids.joinToString("\u0001") }

    private fun recentKey(providerId: String) = stringPreferencesKey("recent_$providerId")
    fun recentChannels(providerId: String): Flow<List<Long>> = ds.data.map { p -> p[recentKey(providerId)]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList() }
    suspend fun pushRecent(providerId: String, channelId: Long) = ds.edit { p ->
        val cur = p[recentKey(providerId)]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        p[recentKey(providerId)] = (listOf(channelId) + cur.filter { it != channelId }).take(11).joinToString(",")
    }

    val codeServer: Flow<String?> = ds.data.map { it[stringPreferencesKey("code_server")] }
    suspend fun setCodeServer(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(stringPreferencesKey("code_server")) else it[stringPreferencesKey("code_server")] = v.trim() }

    val pinSalt: Flow<String?> = ds.data.map { it[stringPreferencesKey("pin_salt")] }
    val pinHash: Flow<String?> = ds.data.map { it[stringPreferencesKey("pin_hash")] }
    suspend fun setPin(saltHex: String?, hashHex: String?) = ds.edit {
        if (saltHex == null || hashHex == null) { it.remove(stringPreferencesKey("pin_salt")); it.remove(stringPreferencesKey("pin_hash")) }
        else { it[stringPreferencesKey("pin_salt")] = saltHex; it[stringPreferencesKey("pin_hash")] = hashHex }
    }

    private fun hiddenKey(providerId: String) = stringPreferencesKey("hidden_groups_$providerId")
    fun hiddenGroups(providerId: String): Flow<Set<String>> = ds.data.map { p -> p[hiddenKey(providerId)]?.split('\u0001')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }
    suspend fun setHiddenGroups(providerId: String, groups: Set<String>) = ds.edit { it[hiddenKey(providerId)] = groups.joinToString("\u0001") }

    private fun hiddenChanKey(providerId: String) = stringPreferencesKey("hidden_channels_$providerId")
    fun hiddenChannels(providerId: String): Flow<Set<Long>> = ds.data.map { p -> p[hiddenChanKey(providerId)]?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet() }
    suspend fun setHiddenChannels(providerId: String, ids: Set<Long>) = ds.edit { it[hiddenChanKey(providerId)] = ids.joinToString(",") }

    private fun lockedKey(providerId: String) = stringPreferencesKey("locked_groups_$providerId")
    fun lockedGroups(providerId: String): Flow<Set<String>> = ds.data.map { p -> p[lockedKey(providerId)]?.split('\u0001')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }
    suspend fun setLockedGroups(providerId: String, groups: Set<String>) = ds.edit { it[lockedKey(providerId)] = groups.joinToString("\u0001") }
}
