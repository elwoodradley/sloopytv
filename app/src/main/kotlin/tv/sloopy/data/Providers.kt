package tv.sloopy.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.sloopy.core.Redact
import tv.sloopy.core.model.StreamContainer
import tv.sloopy.core.xtream.XtreamCredentials
import java.util.UUID

enum class ProviderKind { XTREAM, M3U, DEMO }

/**
 * One provider as stored in the vault. The whole list is one encrypted blob; it is tiny.
 * [password] and [username] are secrets. The object is never logged and never serialised
 * anywhere but the vault.
 */
@Serializable
data class ProviderRecord(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String,                 // Xtream: scheme://host:port. M3U: playlist URL.
    val username: String = "",
    val password: String = "",
    val epgUrl: String? = null,          // M3U only; Xtream derives it
    val container: StreamContainer = StreamContainer.TS,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isPlainHttp: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    fun xtream(): XtreamCredentials = XtreamCredentials.parse(baseUrl, username, password)

    /** Safe for the UI and for logs. */
    fun displayHost(): String = runCatching { java.net.URI(baseUrl).host ?: baseUrl }.getOrDefault("?")
}

@Serializable
private data class ProviderList(val providers: List<ProviderRecord> = emptyList())

class ProviderStore(private val vault: Vault) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<ProviderRecord>> = _providers

    private fun load(): List<ProviderRecord> {
        val list = vault.getString(KEY)?.let { runCatching { json.decodeFromString<ProviderList>(it).providers }.getOrNull() } ?: emptyList()
        list.forEach { Redact.register(it.username, it.password) }
        return list
    }

    private fun save(list: List<ProviderRecord>) {
        list.forEach { Redact.register(it.username, it.password) }
        vault.putString(KEY, json.encodeToString(ProviderList(list)))
        _providers.value = list
    }

    fun get(id: String): ProviderRecord? = _providers.value.firstOrNull { it.id == id }

    fun upsert(p: ProviderRecord) {
        val cur = _providers.value.toMutableList()
        val i = cur.indexOfFirst { it.id == p.id }
        if (i >= 0) cur[i] = p else cur.add(p)
        save(cur)
    }

    fun remove(id: String) {
        val p = get(id)
        save(_providers.value.filterNot { it.id == id })
        if (p != null && _providers.value.none { it.username == p.username }) Redact.forget(p.username)
        if (p != null && _providers.value.none { it.password == p.password }) Redact.forget(p.password)
    }

    companion object {
        private const val KEY = "providers"
        fun newId(): String = UUID.randomUUID().toString()
    }
}
