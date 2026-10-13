package tv.sloopy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class, CategoryEntity::class, ProgrammeEntity::class, EpgChannelEntity::class,
        FavouriteEntity::class, ProgressEntity::class, VodEntity::class, SeriesEntity::class, MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SloopyDb : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun categories(): CategoryDao
    abstract fun programmes(): ProgrammeDao
    abstract fun epgChannels(): EpgChannelDao
    abstract fun favourites(): FavouriteDao
    abstract fun progress(): ProgressDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun meta(): MetaDao

    companion object {
        fun open(context: Context): SloopyDb =
            Room.databaseBuilder(context, SloopyDb::class.java, "sloopy.db")
                .fallbackToDestructiveMigration(dropAllTables = true)   // cache only; credentials live in the vault
                .build()
    }
}
