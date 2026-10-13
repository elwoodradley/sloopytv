package tv.sloopy.core.model

enum class StreamContainer(val ext: String) { TS("ts"), HLS("m3u8") }

data class Account(
    val username: String,
    val status: String,
    val expiresAtEpoch: Long?,        // null = unlimited
    val isTrial: Boolean,
    val activeConnections: Int,
    val maxConnections: Int,
    val allowedFormats: List<String>,
    val serverTimeEpoch: Long?,
    val timezone: String?,
    val serverUrl: String?,
    val httpsPort: Int?,
)

data class Category(val id: String, val name: String, val parentId: String? = null)

data class Channel(
    val id: Long,                     // Xtream stream_id, or a stable hash for M3U entries
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgId: String?,               // maps to XMLTV <channel id="">
    val archive: Boolean,
    val archiveDays: Int,
    val directUrl: String? = null,    // M3U entries carry their own URL
)

data class Programme(
    val epgId: String,
    val startEpoch: Long,
    val stopEpoch: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val episode: String?,
    val icon: String?,
) {
    fun isOnAt(epoch: Long) = epoch in startEpoch until stopEpoch
}

data class Vod(
    val id: Long,
    val number: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val container: String,
    val rating: Double?,
    val addedEpoch: Long?,
    val year: String?,
)

data class VodInfo(
    val id: Long,
    val name: String,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Long?,
    val backdrop: String?,
    val poster: String?,
    val trailer: String?,
    val container: String,
    val rating: Double?,
)

data class Series(
    val id: Long,
    val number: Int,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val backdrop: String?,
    val lastModifiedEpoch: Long?,
)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val container: String,
    val image: String?,
    val plot: String?,
    val durationSecs: Long?,
    val airDate: String?,
)

data class SeriesInfo(
    val series: Series,
    val seasons: List<Int>,
    val episodes: Map<Int, List<Episode>>,
)

/** A short "now / next" entry from Xtream's get_short_epg, used when XMLTV is missing. */
data class ShortEpg(
    val title: String,
    val description: String?,
    val startEpoch: Long,
    val stopEpoch: Long,
    val hasArchive: Boolean,
)
