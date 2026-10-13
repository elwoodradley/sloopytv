package tv.sloopy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class, CategoryEntity::class, ProgrammeEntity::class, EpgChannelEntity::class,
        FavouriteEntity::class, FavouriteItemEntity::class, ProgressEntity::class, VodEntity::class, SeriesEntity::class, MetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SloopyDb : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun categories(): CategoryDao
    abstract fun programmes(): ProgrammeDao
    abstract fun epgChannels(): EpgChannelDao
    abstract fun favourites(): FavouriteDao
    abstract fun favouriteItems(): FavouriteItemDao
    abstract fun progress(): ProgressDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun meta(): MetaDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favourite_items` (`providerId` TEXT NOT NULL, `kind` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`providerId`, `kind`, `itemId`))")
            }
        }

        fun open(context: Context): SloopyDb =
            Room.databaseBuilder(context, SloopyDb::class.java, "sloopy.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)   // cache only; credentials live in the vault
                .build()
    }
}
