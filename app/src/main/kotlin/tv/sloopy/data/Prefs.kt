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
    suspend fun setOsdSeconds(v: Int) = ds.edit { it[intPreferencesKey("osd_seconds")] = v }

    private fun orderKey(providerId: String) = stringPreferencesKey("group_order_$providerId")
    fun groupOrder(providerId: String): Flow<List<String>> = ds.data.map { p -> p[orderKey(providerId)]?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList() }
    suspend fun setGroupOrder(providerId: String, ids: List<String>) = ds.edit { it[orderKey(providerId)] = ids.joinToString("\u0001") }

    val codeServer: Flow<String?> = ds.data.map { it[stringPreferencesKey("code_server")] }
    suspend fun setCodeServer(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(stringPreferencesKey("code_server")) else it[stringPreferencesKey("code_server")] = v.trim() }

    val pinSalt: Flow<String?> = ds.data.map { it[stringPreferencesKey("pin_salt")] }
    val pinHash: Flow<String?> = ds.data.map { it[stringPreferencesKey("pin_hash")] }
    suspend fun setPin(saltHex: String?, hashHex: String?) = ds.edit {
        if (saltHex == null || hashHex == null) { it.remove(stringPreferencesKey("pin_salt")); it.remove(stringPreferencesKey("pin_hash")) }
        else { it[stringPreferencesKey("pin_salt")] = saltHex; it[stringPreferencesKey("pin_hash")] = hashHex }
    }

    private fun lockedKey(providerId: String) = stringPreferencesKey("locked_groups_$providerId")
    fun lockedGroups(providerId: String): Flow<Set<String>> = ds.data.map { p -> p[lockedKey(providerId)]?.split('\u0001')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }
    suspend fun setLockedGroups(providerId: String, groups: Set<String>) = ds.edit { it[lockedKey(providerId)] = groups.joinToString("\u0001") }
}
