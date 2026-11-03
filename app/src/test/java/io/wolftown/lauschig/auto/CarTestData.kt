package io.wolftown.lauschig.auto

import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.local.DeviceLibrary

/** Plain texts so assertions read like the German UI without resources. */
object TestCarTexts : CarTexts {
    override val continueListening = "Weiterhören"
    override val downloads = "Heruntergeladen"
    override val series = "Serien"
    override val libraries = "Bibliotheken"
    override val device = "Auf dem Gerät"
    override val signIn = "In Lauschig auf dem Handy anmelden"
    override fun remaining(sec: Double) = "noch ${(sec / 60).toInt()} min"
    override fun volume(sequence: String) = "Band $sequence"
    override fun volumes(count: Int) = "$count Titel"
}

/**
 * A small account like the emulator's test server: a book library with the 3-volume series "Testreihe" and a
 * multi-file book, a Hörspiel library, a podcast library, and two device titles.
 */
object CarTestData {
    const val S = "aaaaaaaaaaaaaaaa"
    const val DEV = DeviceLibrary.SERVER_ID
    const val LOCAL_A = "local:aaaaaaaaaaaaaaaaaaaaaaaa"
    const val LOCAL_B = "local:bbbbbbbbbbbbbbbbbbbbbbbb"

    fun volume(id: String, n: Int, lib: String = "books") =
        ItemEntity(S, id, lib, "Band $n", author = "Anna Autorin", seriesName = "Testreihe", seriesSequence = "$n", seriesSortKey = n.toDouble(), durationSec = 3600.0, coverUpdatedAt = 7)

    suspend fun seed(db: LauschigDatabase) {
        db.libraryDao().upsertFromServer(
            listOf(
                LibraryEntity(S, "books", "Hörbücher", "book", 0),
                LibraryEntity(S, "hsp", "Hörspiele", "book", 1),
                LibraryEntity(S, "pods", "Podcasts", "podcast", 2),
            ),
        )
        db.itemDao().upsertFromList(
            listOf(
                volume("v1", 1), volume("v2", 2), volume("v3", 3),
                ItemEntity(S, "multi", "books", "Mehrteilig", author = "Bert Bauer", durationSec = 7200.0),
                ItemEntity(S, "f1", "hsp", "Folge 1", author = "Studio"),
                ItemEntity(S, "f2", "hsp", "Folge 2", author = "Studio"),
                ItemEntity(S, "f10", "hsp", "Folge 10", author = "Studio"),
                ItemEntity(S, "pod", "pods", "Der Podcast", author = "Pod Leute", numEpisodes = 2),
            ),
        )
        db.episodeDao().upsert(
            listOf(
                EpisodeEntity(S, "pod", "e1", "Erste Folge", publishedAt = 1_000, durationSec = 1800.0),
                EpisodeEntity(S, "pod", "e2", "Zweite Folge", publishedAt = 2_000, durationSec = 1800.0),
            ),
        )
        val local = DeviceLibrary.LIBRARY_ID
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(DEV, LOCAL_A, local, "Lokal A", author = "Gerät", durationSec = 600.0),
                ItemEntity(DEV, LOCAL_B, local, "Lokal B", durationSec = 600.0),
            ),
        )
        val p = db.progressDao()
        p.saveLocal(S, "v1", 3600.0, 3600.0, isFinished = true, lastUpdate = 1_000)
        p.saveLocal(S, "v2", 600.0, 3600.0, isFinished = false, lastUpdate = 2_000)
        p.saveLocal(S, "pod~e1", 60.0, 1800.0, isFinished = false, lastUpdate = 1_500)
        p.saveLocal(DEV, LOCAL_A, 300.0, 600.0, isFinished = false, lastUpdate = 3_000)
        p.saveUserAction(ProgressEntity(S, "f2", 30.0, 600.0, false, 4_000, hideFromContinue = true))
        // "Band 1" and "Mehrteilig" are downloaded completely; the episode e2 too.
        download(db, "v1", files = 1)
        download(db, "multi", files = 3)
        download(db, "pod~e2", files = 1)
    }

    suspend fun download(db: LauschigDatabase, key: String, files: Int) {
        db.audioFileDao().replaceFromServer(S, key, (0 until files).map { AudioFileEntity(S, key, it, "ino$it", 100.0, 1, "mp3") })
        (0 until files).forEach { db.audioFileDao().setLocal(S, key, it, "/d/$key/$it.mp3", 1) }
        db.downloadDao().upsert(DownloadEntity(S, key, DownloadState.COMPLETE, requestedAt = 1))
    }
}
