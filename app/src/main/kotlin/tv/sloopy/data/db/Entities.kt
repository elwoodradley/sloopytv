package tv.sloopy.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    primaryKeys = ["providerId", "id"],
    indices = [Index("providerId", "categoryId"), Index("providerId", "epgId"), Index("providerId", "number")]
)
data class ChannelEntity(
    val providerId: String,
    val id: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgId: String?,            // resolved id used for guide lookups (provider's, or name-matched)
    val archive: Boolean,
    val archiveDays: Int,
    val directUrl: String?,
    val sortKey: Int,
)

@Entity(tableName = "categories", primaryKeys = ["providerId", "kind", "id"])
data class CategoryEntity(
    val providerId: String,
    val kind: String,              // live | vod | series
    val id: String,
    val name: String,
    val parentId: String?,
    val sortKey: Int,
)

@Entity(
    tableName = "programmes",
    indices = [Index("providerId", "gen", "epgId", "startEpoch"), Index("providerId", "gen", "stopEpoch")]
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val providerId: String,
    val gen: Int,
    val epgId: String,
    val startEpoch: Long,
    val stopEpoch: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val episode: String?,
    val icon: String?,
)

@Entity(tableName = "epg_channels", primaryKeys = ["providerId", "id"])
data class EpgChannelEntity(
    val providerId: String,
    val id: String,
    val name: String,
    val icon: String?,
    val normName: String,
)

@Entity(tableName = "favourites", primaryKeys = ["providerId", "channelId"])
data class FavouriteEntity(val providerId: String, val channelId: Long, val addedAt: Long)

/** Movies and series on the viewer's list. */
@Entity(tableName = "favourite_items", primaryKeys = ["providerId", "kind", "itemId"])
data class FavouriteItemEntity(val providerId: String, val kind: String, val itemId: Long, val addedAt: Long)

@Entity(tableName = "progress", primaryKeys = ["providerId", "kind", "itemId"], indices = [Index("providerId", "updatedAt")])
data class ProgressEntity(
    val providerId: String,
    val kind: String,              // vod | episode
    val itemId: Long,
    val parentId: Long?,           // series id for episodes
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val container: String,
)

@Entity(tableName = "vod", primaryKeys = ["providerId", "id"], indices = [Index("providerId", "categoryId"), Index("providerId", "addedEpoch")])
data class VodEntity(
    val providerId: String,
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

@Entity(tableName = "series", primaryKeys = ["providerId", "id"], indices = [Index("providerId", "categoryId"), Index("providerId", "lastModifiedEpoch")])
data class SeriesEntity(
    val providerId: String,
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

@Entity(tableName = "meta", primaryKeys = ["providerId", "key"])
data class MetaEntity(val providerId: String, val key: String, val value: String)

/** Lightweight projection for the channel list and guide rows. */
data class NowNext(
    val epgId: String,
    val startEpoch: Long,
    val stopEpoch: Long,
    val title: String,
)
