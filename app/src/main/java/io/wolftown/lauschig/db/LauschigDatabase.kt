package io.wolftown.lauschig.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.ChapterDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ExternalSeriesDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.dao.TitleAudioDao
import io.wolftown.lauschig.db.dao.BookmarkDao
import io.wolftown.lauschig.db.dao.CollectionDao
import io.wolftown.lauschig.db.dao.HistoryDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.db.dao.LocalLibraryDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.db.entities.CollectionEntity
import io.wolftown.lauschig.db.entities.CollectionEntryEntity
import io.wolftown.lauschig.db.entities.HistorySessionEntity
import io.wolftown.lauschig.db.entities.QueueEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ExternalSeriesEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.db.entities.TitleAudioEntity
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.db.entities.LocalTitleEntity

@Database(
    entities = [
        LibraryEntity::class, ItemEntity::class, ChapterEntity::class, AudioFileEntity::class,
        ProgressEntity::class, LocalSessionEntity::class, DownloadEntity::class, EpisodeEntity::class,
        ExternalSeriesEntity::class, TitleAudioEntity::class,
        BookmarkEntity::class, QueueEntity::class, HistorySessionEntity::class, CollectionEntity::class, CollectionEntryEntity::class,
        LocalFolderEntity::class, LocalTitleEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)],
)
@TypeConverters(Converters::class)
abstract class LauschigDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun itemDao(): ItemDao
    abstract fun catalogDao(): CatalogDao
    abstract fun chapterDao(): ChapterDao
    abstract fun audioFileDao(): AudioFileDao
    abstract fun progressDao(): ProgressDao
    abstract fun sessionDao(): SessionDao
    abstract fun downloadDao(): DownloadDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun externalSeriesDao(): ExternalSeriesDao
    abstract fun titleAudioDao(): TitleAudioDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun queueDao(): QueueDao
    abstract fun historyDao(): HistoryDao
    abstract fun collectionDao(): CollectionDao
    abstract fun localLibraryDao(): LocalLibraryDao
}
