package io.wolftown.lauschig.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Version 1 is what the first test installs carry; their progress must survive the update. The v1 database is
 * built from the exported schema (MigrationTestHelper cannot read unit-test assets under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test fun v1ToV2_keepsProgress_andDefaultsHideToFalse() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1)")
            db.version = 1
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val p = room.progressDao().get("s", "a")!!
            assertEquals(12.5, p.currentTimeSec, 0.0)
            assertFalse(p.hideFromContinue)
        } finally {
            room.close()
        }
    }

    @Test fun v2ToV3_keepsData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v3.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/2.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO items (serverId, id, libraryId, title, durationSec, coverUpdatedAt, genres, tags, updatedAt, detailsLoaded, orphaned) VALUES ('s', 'a', 'l', 'T', 1.0, 0, '', '', 5, 0, 0)")
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1)")
            db.version = 2
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val p = room.progressDao().get("s", "a")!!
            assertEquals(12.5, p.currentTimeSec, 0.0)
            assertFalse(p.hideFromContinue)
            val item = room.itemDao().get("s", "a")!!
            assertEquals("T", item.title)
            assertEquals(0L, item.addedAt)
            assertEquals(null, item.publishedYear)
        } finally {
            room.close()
        }
    }

    @Test fun v3ToV4_keepsData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v4.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/3.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO items (serverId, id, libraryId, title, durationSec, coverUpdatedAt, genres, tags, updatedAt, addedAt, publishedYear, detailsLoaded, orphaned) VALUES ('s', 'a', 'l', 'T', 1.0, 0, '', '', 5, 9, '2001', 0, 0)")
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.execSQL("INSERT INTO downloads (serverId, itemId, state, requestedAt) VALUES ('s', 'a', 'COMPLETE', 3)")
            db.execSQL("INSERT INTO local_sessions (id, serverId, itemId, startTimeSec, currentTimeSec, timeListeningSec, startedAt, updatedAt, synced) VALUES ('sess', 's', 'a', 0.0, 5.0, 5.0, 1, 2, 0)")
            db.version = 3
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val item = room.itemDao().get("s", "a")!!
            assertEquals("T", item.title)
            assertEquals("2001", item.publishedYear)
            assertEquals(null, item.asin)
            assertEquals(null, item.language)
            assertEquals(0, item.numEpisodes)
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(DownloadState.COMPLETE, room.downloadDao().get("s", "a")!!.state)
            assertEquals(1, room.sessionDao().unsynced("s").size)
            assertEquals(emptyList<EpisodeEntity>(), room.episodeDao().observeForItem("s", "a").first())
        } finally {
            room.close()
        }
    }

    @Test fun v4ToV5_keepsData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v5.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/4.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO items (serverId, id, libraryId, title, durationSec, coverUpdatedAt, genres, tags, updatedAt, addedAt, publishedYear, asin, language, numEpisodes, detailsLoaded, orphaned) VALUES ('s', 'a', 'l', 'T', 1.0, 0, '', '', 5, 9, '2001', 'B0X', 'German', 3, 0, 0)")
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.execSQL("INSERT INTO downloads (serverId, itemId, state, requestedAt) VALUES ('s', 'a', 'COMPLETE', 3)")
            db.execSQL("INSERT INTO local_sessions (id, serverId, itemId, startTimeSec, currentTimeSec, timeListeningSec, startedAt, updatedAt, synced) VALUES ('sess', 's', 'a', 0.0, 5.0, 5.0, 1, 2, 0)")
            db.version = 4
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val item = room.itemDao().get("s", "a")!!
            assertEquals("T", item.title)
            assertEquals("2001", item.publishedYear)
            assertEquals("B0X", item.asin)
            assertEquals("German", item.language)
            assertEquals(3, item.numEpisodes)
            assertEquals(null, room.externalSeriesDao().get("s", "l", "x"))
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(DownloadState.COMPLETE, room.downloadDao().get("s", "a")!!.state)
            assertEquals(1, room.sessionDao().unsynced("s").size)
            assertEquals(emptyList<EpisodeEntity>(), room.episodeDao().observeForItem("s", "a").first())
        } finally {
            room.close()
        }
    }

    @Test fun v5ToV6_keepsProgress_andAddsEmptyTitleAudio() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v6.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/5.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.version = 5
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(null, room.titleAudioDao().get("s", "a"))
            room.titleAudioDao().setSpeed("s", "a", 1.5f)
            room.titleAudioDao().setBoost("s", "a", 6)
            val row = room.titleAudioDao().get("s", "a")!!
            assertEquals(1.5f, row.speed!!, 0f)
            assertEquals(6, row.boostDb)
        } finally {
            room.close()
        }
    }

    @Test fun v6ToV7_keepsData_andAddsWorkingPhase3bTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v7.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/6.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.execSQL("INSERT INTO local_sessions (id, serverId, itemId, startTimeSec, currentTimeSec, timeListeningSec, startedAt, updatedAt, synced) VALUES ('sess', 's', 'a', 0.0, 5.0, 5.0, 1, 2, 0)")
            db.execSQL("INSERT INTO title_audio (serverId, itemId, speed, boostDb) VALUES ('s', 'a', 1.25, NULL)")
            db.version = 6
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(1, room.sessionDao().unsynced("s").size)
            assertEquals(1.25f, room.titleAudioDao().get("s", "a")!!.speed!!, 0f)
            room.bookmarkDao().save("s", "a", 60.0, "n", now = 1)
            assertEquals(1, room.bookmarkDao().observeForKey("s", "a").first().size)
            room.queueDao().add("s", "a", 1)
            assertEquals(listOf("a"), room.queueDao().entries("s").map { it.itemKey })
            assertEquals(emptyList<Any>(), room.historyDao().observeForKey("s", "a").first())
            assertEquals(emptyList<Any>(), room.collectionDao().observeByKind("s", io.wolftown.lauschig.db.entities.CollectionKind.PLAYLIST).first())
        } finally {
            room.close()
        }
    }
    @Test fun v7ToV8_keepsData_andAddsWorkingLocalTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v8.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/7.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.execSQL("INSERT INTO bookmarks (serverId, itemKey, timeSec, note, createdAt, pending, onServer, rev) VALUES ('s', 'a', 60.0, 'n', 1, 'NONE', 1, 0)")
            db.execSQL("INSERT INTO queue (serverId, itemKey, position, addedAt) VALUES ('s', 'a', 0, 1)")
            db.version = 7
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals("n", room.bookmarkDao().get("s", "a", 60.0)!!.note)
            assertEquals(listOf("a"), room.queueDao().entries("s").map { it.itemKey })
            val dao = room.localLibraryDao()
            dao.addFolder(io.wolftown.lauschig.db.entities.LocalFolderEntity("content://tree/x", "Hörbücher", addedAt = 1))
            assertEquals(listOf("Hörbücher"), dao.folders().map { it.name })
            assertEquals(emptyList<Any>(), dao.titlesIn("content://tree/x"))
        } finally {
            room.close()
        }
    }

    @Test fun v8ToV9_keepsData_andAddsAWorkingListeningSessionsTable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v9.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/8.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1, 0)")
            db.execSQL("INSERT INTO local_sessions (id, serverId, itemId, startTimeSec, currentTimeSec, timeListeningSec, startedAt, updatedAt, synced) VALUES ('sess', 's', 'a', 0.0, 5.0, 5.0, 1, 2, 0)")
            db.execSQL("INSERT INTO history_sessions (serverId, id, itemKey, startedAt, updatedAt, startTimeSec, currentTimeSec, timeListeningSec, deviceName) VALUES ('s', 'h', 'a', 1, 2, 0.0, 5.0, 5.0, NULL)")
            db.version = 8
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(listOf("sess"), room.sessionDao().observeAll("s").first().map { it.id })
            assertEquals(listOf("h"), room.historyDao().observeForKey("s", "a").first().map { it.id })
            val dao = room.listeningSessionDao()
            assertEquals(emptyList<Any>(), dao.observeAll("s").first())
            dao.upsertAll(listOf(io.wolftown.lauschig.db.entities.ListeningSessionEntity("s", "x", "a", "T", null, 1, 2, 30.0, 100.0, 40.0)))
            assertEquals(30.0, dao.observeAll("s").first().single().timeListeningSec, 0.0)
        } finally {
            room.close()
        }
    }

    @Test fun v9ToV10_keepsData_andAddsAWorkingAchievementSeenTable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test-v10.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/9.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty, hideFromContinue) VALUES ('s', 'a', 12.5, 100.0, 1, 7, 0, 0)")
            db.execSQL("INSERT INTO listening_sessions (serverId, id, itemKey, title, author, startedAt, updatedAt, timeListeningSec, durationSec, currentTimeSec) VALUES ('s', 'x', 'a', 'T', NULL, 1, 2, 30.0, 100.0, 40.0)")
            db.version = 9
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            assertEquals(12.5, room.progressDao().get("s", "a")!!.currentTimeSec, 0.0)
            assertEquals(30.0, room.listeningSessionDao().observeAll("s").first().single().timeListeningSec, 0.0)
            val dao = room.achievementSeenDao()
            assertEquals(emptyList<Any>(), dao.observeAll("s").first())
            dao.insertAll(listOf(io.wolftown.lauschig.db.entities.AchievementSeenEntity("s", "streak", 2, 5)))
            assertEquals(5L, dao.observeAll("s").first().single().seenAt)
        } finally {
            room.close()
        }
    }
}
