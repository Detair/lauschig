package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LauschigDatabase =
        Room.databaseBuilder(context, LauschigDatabase::class.java, "lauschig.db").build()

    @Provides fun libraryDao(db: LauschigDatabase) = db.libraryDao()
    @Provides fun itemDao(db: LauschigDatabase) = db.itemDao()
    @Provides fun catalogDao(db: LauschigDatabase) = db.catalogDao()
    @Provides fun chapterDao(db: LauschigDatabase) = db.chapterDao()
    @Provides fun audioFileDao(db: LauschigDatabase) = db.audioFileDao()
    @Provides fun progressDao(db: LauschigDatabase) = db.progressDao()
    @Provides fun sessionDao(db: LauschigDatabase) = db.sessionDao()
    @Provides fun downloadDao(db: LauschigDatabase) = db.downloadDao()
    @Provides fun episodeDao(db: LauschigDatabase) = db.episodeDao()
    @Provides fun externalSeriesDao(db: LauschigDatabase) = db.externalSeriesDao()
    @Provides fun titleAudioDao(db: LauschigDatabase) = db.titleAudioDao()
    @Provides fun bookmarkDao(db: LauschigDatabase) = db.bookmarkDao()
    @Provides fun queueDao(db: LauschigDatabase) = db.queueDao()
    @Provides fun historyDao(db: LauschigDatabase) = db.historyDao()
    @Provides fun collectionDao(db: LauschigDatabase) = db.collectionDao()
    @Provides fun localLibraryDao(db: LauschigDatabase) = db.localLibraryDao()
}
