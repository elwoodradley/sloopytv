package tv.sloopy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE providerId = :p ORDER BY sortKey")
    fun all(p: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE providerId = :p ORDER BY sortKey")
    suspend fun allOnce(p: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE providerId = :p AND categoryId = :cat ORDER BY sortKey")
    fun byCategory(p: String, cat: String): Flow<List<ChannelEntity>>

    @Query("SELECT c.* FROM channels c JOIN favourites f ON f.providerId = c.providerId AND f.channelId = c.id WHERE c.providerId = :p ORDER BY f.addedAt")
    fun favourites(p: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE providerId = :p AND id = :id")
    suspend fun byId(p: String, id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE providerId = :p AND number = :num ORDER BY sortKey LIMIT 1")
    suspend fun byNumber(p: String, num: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE providerId = :p AND name LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun search(p: String, q: String, limit: Int = 200): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE providerId = :p")
    suspend fun count(p: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE providerId = :p")
    suspend fun deleteAll(p: String)

    @Transaction
    suspend fun replaceAll(p: String, list: List<ChannelEntity>) {
        deleteAll(p)
        list.chunked(2000).forEach { insertAll(it) }
    }

    @Query("UPDATE channels SET epgId = :epgId WHERE providerId = :p AND id = :id")
    suspend fun setEpgId(p: String, id: Long, epgId: String?)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE providerId = :p AND kind = :kind ORDER BY sortKey")
    fun byKind(p: String, kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE providerId = :p AND kind = :kind ORDER BY sortKey")
    suspend fun byKindOnce(p: String, kind: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE providerId = :p AND kind = :kind")
    suspend fun deleteKind(p: String, kind: String)

    @Transaction
    suspend fun replaceKind(p: String, kind: String, list: List<CategoryEntity>) {
        deleteKind(p, kind)
        insertAll(list)
    }
}

@Dao
interface ProgrammeDao {
    @Query("SELECT * FROM programmes WHERE providerId = :p AND gen = :gen AND epgId = :epgId AND stopEpoch > :from AND startEpoch < :to ORDER BY startEpoch")
    suspend fun window(p: String, gen: Int, epgId: String, from: Long, to: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE providerId = :p AND gen = :gen AND epgId IN (:ids) AND stopEpoch > :from AND startEpoch < :to ORDER BY startEpoch")
    suspend fun windowMany(p: String, gen: Int, ids: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /** Blocking on purpose: the guide store calls this on its own IO thread so it never queues behind the sweep's writes on Room's executor. */
    @Query("SELECT * FROM programmes WHERE providerId = :p AND gen = :gen AND epgId IN (:ids) AND stopEpoch > :from AND startEpoch < :to ORDER BY startEpoch")
    fun windowManyBlocking(p: String, gen: Int, ids: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /**
     * One channel's rows that have not ended by [from] and start before [to], through the
     * (providerId, gen, epgId, stopEpoch) index: a range scan of a few dozen rows. The guide
     * store reads this way, one channel at a time, because the IN-list form let SQLite pick a
     * plan that walked far more than it returned.
     */
    @Query("SELECT * FROM programmes INDEXED BY index_programmes_providerId_gen_epgId_stopEpoch WHERE providerId = :p AND gen = :gen AND epgId = :epgId AND stopEpoch > :from AND startEpoch < :to ORDER BY startEpoch")
    fun windowBlocking(p: String, gen: Int, epgId: String, from: Long, to: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE providerId = :p AND gen = :gen AND epgId IN (:ids) AND stopEpoch > :now ORDER BY startEpoch")
    suspend fun fromNow(p: String, gen: Int, ids: List<String>, now: Long): List<ProgrammeEntity>

    @Query("SELECT epgId, startEpoch, stopEpoch, title FROM programmes WHERE providerId = :p AND gen = :gen AND epgId IN (:ids) AND startEpoch <= :now AND stopEpoch > :now")
    suspend fun nowPlaying(p: String, gen: Int, ids: List<String>, now: Long): List<NowNext>

    @Query("SELECT * FROM programmes WHERE providerId = :p AND gen = :gen AND title LIKE '%' || :q || '%' AND stopEpoch > :now ORDER BY startEpoch LIMIT :limit")
    suspend fun search(p: String, gen: Int, q: String, now: Long, limit: Int = 200): List<ProgrammeEntity>

    @Query("SELECT MIN(startEpoch) FROM programmes WHERE providerId = :p AND gen = :gen")
    suspend fun earliest(p: String, gen: Int): Long?

    @Query("SELECT MAX(stopEpoch) FROM programmes WHERE providerId = :p AND gen = :gen")
    suspend fun latest(p: String, gen: Int): Long?

    @Query("SELECT COUNT(*) FROM programmes WHERE providerId = :p AND gen = :gen")
    suspend fun count(p: String, gen: Int): Int

    /** Blocking on purpose: called from the streaming XML parser's sink on an IO thread. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBatchBlocking(list: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE providerId = :p AND gen = :gen AND epgId = :epgId")
    suspend fun deleteFor(p: String, gen: Int, epgId: String)

    @Query("DELETE FROM programmes WHERE providerId = :p AND gen != :keepGen")
    suspend fun deleteOtherGens(p: String, keepGen: Int)

    @Query("DELETE FROM programmes WHERE providerId = :p AND gen = :gen")
    suspend fun deleteGen(p: String, gen: Int)

    @Query("DELETE FROM programmes WHERE providerId = :p")
    suspend fun deleteAll(p: String)
}

@Dao
interface EpgChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBatchBlocking(list: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channels WHERE providerId = :p")
    suspend fun all(p: String): List<EpgChannelEntity>

    @Query("DELETE FROM epg_channels WHERE providerId = :p")
    suspend fun deleteAll(p: String)
}

@Dao
interface FavouriteDao {
    @Query("SELECT channelId FROM favourites WHERE providerId = :p")
    fun ids(p: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE providerId = :p AND channelId = :id")
    suspend fun remove(p: String, id: Long)
}

@Dao
interface FavouriteItemDao {
    @Query("SELECT itemId FROM favourite_items WHERE providerId = :p AND kind = :kind ORDER BY addedAt DESC")
    fun ids(p: String, kind: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavouriteItemEntity)

    @Query("DELETE FROM favourite_items WHERE providerId = :p AND kind = :kind AND itemId = :id")
    suspend fun remove(p: String, kind: String, id: Long)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE providerId = :p ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(p: String, limit: Int = 30): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE providerId = :p AND kind = :kind AND itemId = :id")
    suspend fun get(p: String, kind: String, id: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE providerId = :p AND kind = 'episode' AND parentId = :seriesId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestEpisode(p: String, seriesId: Long): ProgressEntity?

    @Upsert
    suspend fun upsert(e: ProgressEntity)

    @Query("DELETE FROM progress WHERE providerId = :p AND kind = :kind AND itemId = :id")
    suspend fun remove(p: String, kind: String, id: Long)
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod WHERE providerId = :p AND categoryId = :cat ORDER BY number LIMIT :limit")
    suspend fun byCategory(p: String, cat: String, limit: Int = 500): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :p ORDER BY addedEpoch DESC LIMIT :limit")
    suspend fun recent(p: String, limit: Int = 40): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :p AND id = :id")
    suspend fun byId(p: String, id: Long): VodEntity?

    @Query("SELECT * FROM vod WHERE providerId = :p AND id IN (:ids)")
    suspend fun byIds(p: String, ids: List<Long>): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :p AND name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit")
    suspend fun search(p: String, q: String, limit: Int = 100): List<VodEntity>

    @Query("SELECT COUNT(*) FROM vod WHERE providerId = :p")
    suspend fun count(p: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<VodEntity>)

    @Query("DELETE FROM vod WHERE providerId = :p")
    suspend fun deleteAll(p: String)

    @Transaction
    suspend fun replaceAll(p: String, list: List<VodEntity>) {
        deleteAll(p)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE providerId = :p AND categoryId = :cat ORDER BY number LIMIT :limit")
    suspend fun byCategory(p: String, cat: String, limit: Int = 500): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE providerId = :p ORDER BY lastModifiedEpoch DESC LIMIT :limit")
    suspend fun recent(p: String, limit: Int = 40): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE providerId = :p AND id = :id")
    suspend fun byId(p: String, id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE providerId = :p AND id IN (:ids)")
    suspend fun byIds(p: String, ids: List<Long>): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE providerId = :p AND name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit")
    suspend fun search(p: String, q: String, limit: Int = 100): List<SeriesEntity>

    @Query("SELECT COUNT(*) FROM series WHERE providerId = :p")
    suspend fun count(p: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE providerId = :p")
    suspend fun deleteAll(p: String)

    @Transaction
    suspend fun replaceAll(p: String, list: List<SeriesEntity>) {
        deleteAll(p)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE providerId = :p AND `key` = :key")
    suspend fun get(p: String, key: String): String?

    @Upsert
    suspend fun set(m: MetaEntity)

    @Query("DELETE FROM meta WHERE providerId = :p")
    suspend fun deleteAll(p: String)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE providerId = :p ORDER BY startEpoch DESC")
    fun all(p: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Insert
    suspend fun insert(r: RecordingEntity): Long

    @Upsert
    suspend fun upsert(r: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /** Rows left as "recording" by a process that died. */
    @Query("UPDATE recordings SET status = CASE WHEN bytes > 0 THEN 'done' ELSE 'failed' END, error = CASE WHEN bytes > 0 THEN error ELSE 'Interrupted' END WHERE status = 'recording'")
    suspend fun finishStale()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE providerId = :p ORDER BY startEpoch")
    fun all(p: String): Flow<List<ReminderEntity>>

    /** The next reminder whose programme is starting (a minute early) or already on. */
    @Query("SELECT * FROM reminders WHERE startEpoch - 60 <= :now AND stopEpoch > :now ORDER BY startEpoch LIMIT 1")
    suspend fun due(now: Long): ReminderEntity?

    @Insert
    suspend fun insert(r: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE providerId = :p AND channelId = :channelId AND startEpoch = :startEpoch")
    suspend fun deleteFor(p: String, channelId: Long, startEpoch: Long)

    @Query("DELETE FROM reminders WHERE stopEpoch <= :now")
    suspend fun purge(now: Long)
}
