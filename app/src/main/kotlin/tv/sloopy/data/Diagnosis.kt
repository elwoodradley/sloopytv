package tv.sloopy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import tv.sloopy.core.xtream.XtreamApi
import tv.sloopy.data.db.ChannelEntity
import java.util.concurrent.TimeUnit

/** "Is it me or them": four direct tests, each a real request, no guessing. */
data class Verdict(val headline: String, val detail: String)

object Diagnosis {
    suspend fun run(record: ProviderRecord, codeServer: String, failed: ChannelEntity, others: List<ChannelEntity>, liveUrl: (ChannelEntity) -> String?): Verdict = withContext(Dispatchers.IO) {
        val quick = AndroidHttp.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
        fun reach(url: String): Int = runCatching { quick.newCall(Request.Builder().url(url).get().build()).execute().use { it.code } }.getOrDefault(-1)
        fun streamStarts(url: String): Int = runCatching {
            quick.newCall(Request.Builder().url(url).get().build()).execute().use { r -> if (!r.isSuccessful) r.code else { val b = r.body?.byteStream() ?: return@use -2; val buf = ByteArray(8192); if (b.read(buf) > 0) 200 else -2 } }
        }.getOrDefault(-1)

        // 1. Internet at all?
        val internet = codeServer.startsWith("http") && reach(codeServer.trimEnd('/') + "/config") == 200 || reach("https://clients3.google.com/generate_204") == 204
        if (!internet) return@withContext Verdict("Your Wi-Fi", "This box cannot reach the internet. Check the network, not the provider.")
        if (record.kind != ProviderKind.XTREAM) return@withContext Verdict("The stream", "The internet is fine; this stream is not answering.")
        // 2. Provider's login API?
        val account = runCatching { XtreamApi(AndroidHttp.core, record.xtream()).authenticate() }
        if (account.isFailure) return@withContext Verdict("Your provider", "The provider's server is not answering at all. Everyone on it is down, not just you.")
        val a = account.getOrThrow()
        if (!a.status.equals("Active", ignoreCase = true)) return@withContext Verdict("Your account", "The provider says this account is ${a.status.lowercase()}.")
        if (a.maxConnections in 1..a.activeConnections) return@withContext Verdict("Another device", "Your account is already streaming on ${a.activeConnections} of ${a.maxConnections} allowed device${if (a.maxConnections == 1) "" else "s"}. Stop it there and try again.")
        // 3. Do two other channels start?
        val probes = others.filter { it.id != failed.id }.shuffled().take(2).mapNotNull { c -> liveUrl(c)?.let { streamStarts(it) } }
        if (probes.isNotEmpty() && probes.all { it == 200 }) return@withContext Verdict("This channel only", "Other channels play. The provider's feed for this one is down; a copy may work. Hold OK for Copies or Report.")
        if (probes.isNotEmpty() && probes.none { it == 200 }) return@withContext Verdict("Your provider's streams", "The login works but no channel starts. The provider's stream servers are down right now.")
        Verdict("Unclear", "The login works and the internet is fine. Try again in a moment.")
    }
}
