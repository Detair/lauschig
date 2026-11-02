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
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ExternalSeriesEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.db.entities.TitleAudioEntity

@Database(
    entities = [
        LibraryEntity::class, ItemEntity::class, ChapterEntity::class, AudioFileEntity::class,
        ProgressEntity::class, LocalSessionEntity::class, DownloadEntity::class, EpisodeEntity::class,
        ExternalSeriesEntity::class, TitleAudioEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
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
}
