package tv.sloopy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class, CategoryEntity::class, ProgrammeEntity::class, EpgChannelEntity::class,
        FavouriteEntity::class, FavouriteItemEntity::class, ProgressEntity::class, VodEntity::class, SeriesEntity::class, MetaEntity::class, RecordingEntity::class, ReminderEntity::class,
    ],
    version = 4,
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
    abstract fun recordings(): RecordingDao
    abstract fun reminders(): ReminderDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favourite_items` (`providerId` TEXT NOT NULL, `kind` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`providerId`, `kind`, `itemId`))")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` TEXT NOT NULL, `channelId` INTEGER NOT NULL, `channelName` TEXT NOT NULL, `title` TEXT NOT NULL, `startEpoch` INTEGER NOT NULL, `stopEpoch` INTEGER NOT NULL, `status` TEXT NOT NULL, `uri` TEXT, `bytes` INTEGER NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_providerId_startEpoch` ON `recordings` (`providerId`, `startEpoch`)")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` TEXT NOT NULL, `channelId` INTEGER NOT NULL, `channelName` TEXT NOT NULL, `title` TEXT NOT NULL, `startEpoch` INTEGER NOT NULL, `stopEpoch` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_providerId_startEpoch` ON `reminders` (`providerId`, `startEpoch`)")
            }
        }

        fun open(context: Context): SloopyDb =
            Room.databaseBuilder(context, SloopyDb::class.java, "sloopy.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)   // cache only; credentials live in the vault
                .build()
    }
}
