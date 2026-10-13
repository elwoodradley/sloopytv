package tv.tube.core.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Xtream panels are wildly inconsistent: numbers arrive as strings, booleans as 0/1/"1"/true,
 * arrays as maps keyed by index, missing fields as "" or null. Everything goes through these
 * tolerant accessors instead of typed @Serializable classes so one odd panel cannot break
 * the whole catalogue.
 */
internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    allowTrailingComma = true
}

internal fun parseJson(bytes: ByteArray): JsonElement = lenientJson.parseToJsonElement(bytes.decodeToString())

internal val JsonElement?.obj: JsonObject? get() = (this as? JsonObject)
internal val JsonElement?.arr: JsonArray? get() = (this as? JsonArray)

internal fun JsonObject?.str(key: String): String? {
    val e = this?.get(key) ?: return null
    return when (e) {
        is JsonNull -> null
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun JsonObject?.long(key: String): Long? {
    val e = this?.get(key) ?: return null
    return when (e) {
        is JsonPrimitive -> e.contentOrNull?.trim()?.let { s ->
            s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
        }
        else -> null
    }
}

internal fun JsonObject?.int(key: String): Int? = long(key)?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }

internal fun JsonObject?.double(key: String): Double? {
    val e = this?.get(key) ?: return null
    return (e as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
}

internal fun JsonObject?.bool(key: String): Boolean {
    val e = this?.get(key) ?: return false
    val s = (e as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return false
    return s == "1" || s == "true" || s == "yes"
}

/** Some panels send `["a","b"]`, others `"a"`, others `{"0":"a"}`. */
internal fun JsonObject?.strList(key: String): List<String> {
    val e = this?.get(key) ?: return emptyList()
    return when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        is JsonObject -> e.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}

/** First string of a list field, used for backdrop_path. */
internal fun JsonObject?.firstStr(key: String): String? = strList(key).firstOrNull()

/** A list that may arrive as an array or as an object keyed by index. */
internal fun JsonElement?.asList(): List<JsonObject> = when (this) {
    is JsonArray -> this.mapNotNull { it as? JsonObject }
    is JsonObject -> this.values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

internal fun JsonElement.requireObject(what: String): JsonObject =
    this as? JsonObject ?: throw XtreamException("Expected an object for $what")

internal fun JsonElement.requireArrayish(what: String): List<JsonObject> = when (this) {
    is JsonArray -> jsonArray.mapNotNull { it as? JsonObject }
    is JsonObject -> jsonObject.values.mapNotNull { it as? JsonObject }
    else -> throw XtreamException("Expected a list for $what")
}

internal fun String.clip(max: Int): String = if (length <= max) this else substring(0, max)
