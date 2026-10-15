package tv.sloopy.pay

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Request
import tv.sloopy.data.AndroidHttp

/** What the code server says about the paywall. An empty product means the app is free. */
data class PayConfig(
    val gumroadProduct: String, val buyUrl: String, val price: String,
    val operatorName: String = "",
    val announcementId: String = "", val announcementText: String = "",
    /** Dead-channel reports: nothing in the app shows until the operator turns this on. */
    val reportsEnabled: Boolean = false,
) {
    val enabled: Boolean get() = gumroadProduct.isNotBlank()
}

sealed class Verdict {
    data object Valid : Verdict()
    data class Invalid(val why: String) : Verdict()
    data object Unknown : Verdict()   // could not reach Gumroad: keep whatever we had
}

/** Gumroad license keys: the operator sells a product there, buyers type the key once. */
object License {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun config(server: String): PayConfig? = withContext(Dispatchers.IO) {
        val base = server.trim().trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) return@withContext null
        runCatching {
            AndroidHttp.client.newCall(Request.Builder().url("$base/config").get().build()).execute().use { r ->
                val root = json.parseToJsonElement(r.body?.string() ?: "") as? JsonObject ?: return@use null
                PayConfig(
                    root.str("gumroadProduct").orEmpty(), root.str("buyUrl").orEmpty(), root.str("price").orEmpty(),
                    operatorName = root.str("operatorName").orEmpty(),
                    announcementId = root.str("announcementId").orEmpty(), announcementText = root.str("announcementText").orEmpty(),
                    reportsEnabled = (root["reportsEnabled"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }.getOrNull()
    }

    suspend fun verify(product: String, key: String): Verdict = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("product_id", product).add("license_key", key.trim()).add("increment_uses_count", "false").build()
        val req = Request.Builder().url("https://api.gumroad.com/v2/licenses/verify").post(body).build()
        val text = try {
            AndroidHttp.client.newCall(req).execute().use { r -> r.body?.string() ?: "" }
        } catch (e: IOException) { return@withContext Verdict.Unknown }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@withContext Verdict.Unknown
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) return@withContext Verdict.Invalid(root.str("message") ?: "That key is not valid")
        val p = root["purchase"] as? JsonObject
        if (p?.get("refunded")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return@withContext Verdict.Invalid("That purchase was refunded")
        if (p?.get("chargebacked")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return@withContext Verdict.Invalid("That purchase was charged back")
        if (p?.str("subscription_cancelled_at") != null || p?.str("subscription_failed_at") != null) return@withContext Verdict.Invalid("That subscription has ended")
        Verdict.Valid
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
