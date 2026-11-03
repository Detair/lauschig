package io.wolftown.lauschig.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AccountDataDao
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.sync.InMemorySyncStamps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** What one account owns on the device, and that purging it never touches another server or the device library. */
@RunWith(RobolectricTestRunner::class)
class AccountDataTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val dev = DeviceLibrary.SERVER_ID
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var files: DownloadFiles
    private val stamps = InMemorySyncStamps()
    private val forgottenCovers = mutableListOf<String>()
    private lateinit var data: AccountData

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        root = File(ctx.cacheDir, "account-data-${System.nanoTime()}/downloads").apply { mkdirs() }
        files = DownloadFiles(root, db.audioFileDao())
        data = AccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), files, stamps, forgottenCovers::add)
    }

    @After fun tearDown() {
        db.close()
        root.parentFile!!.deleteRecursively()
    }

    private fun sql() = db.openHelper.writableDatabase

    /** Every table of the schema with a `serverId` column (Room's own tables excluded). */
    private fun tablesWithServerId(): Set<String> {
        val tables = mutableListOf<String>()
        sql().query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        return tables.filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }
            .filter { t -> columns(t).any { it.name == "serverId" } }.toSet()
    }

    private data class Column(val name: String, val type: String)

    private fun columns(table: String): List<Column> {
        val out = mutableListOf<Column>()
        sql().query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) out += Column(c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("type")))
        }
        return out
    }

    /** One row with generated values; [n] makes every key column unique. */
    private fun insertGeneric(table: String, serverId: String, n: Int) {
        val cols = columns(table)
        val values: List<Any> = cols.map { col ->
            when {
                col.name == "serverId" -> serverId
                col.type.equals("INTEGER", true) -> n.toLong()
                col.type.equals("REAL", true) -> n.toDouble()
                else -> "v$n"
            }
        }
        sql().execSQL(
            "INSERT INTO `$table` (${cols.joinToString { "`${it.name}`" }}) VALUES (${cols.joinToString { "?" }})",
            values.toTypedArray<Any>(),
        )
    }

    private fun count(table: String, serverId: String): Int =
        sql().query("SELECT COUNT(*) FROM `$table` WHERE serverId = ?", arrayOf(serverId)).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun noOtherServerColumn_slipsThrough() {
        // An account table keyed `server_id`, `ownerServerId` or the like would not be purged by serverId: flag it.
        val tables = mutableListOf<String>()
        sql().query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        val serverColumns = tables.filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }
            .flatMap { t -> columns(t).filter { it.name.contains("server", ignoreCase = true) }.map { t to it.name } }
        // Known columns that only mention the server but key nothing ("the server has this bookmark").
        val notAKey = setOf("bookmarks" to "onServer")
        val unhandled = serverColumns.filterNot { (t, c) -> (c == "serverId" && t in AccountDataDao.SERVER_TABLES) || (t to c) in notAKey }
        assertEquals(emptyList<Pair<String, String>>(), unhandled)
    }

    @Test fun everyTableWithAServerId_isPurged_andTheListMatchesTheSchema() {
        // A new entity with a serverId column fails here until AccountDataDao deletes it (or it is listed as not account data).
        assertEquals(tablesWithServerId(), AccountDataDao.SERVER_TABLES.toSet())
        assertEquals(AccountDataDao.SERVER_TABLES.size, AccountDataDao.SERVER_TABLES.toSet().size)
    }

    @Test fun purge_deletesEveryRowOfThisServer_andNothingElse() = runBlocking {
        val tables = tablesWithServerId()
        tables.forEach { t ->
            insertGeneric(t, a, 1)
            insertGeneric(t, a, 2)
            insertGeneric(t, b, 3)
            insertGeneric(t, dev, 4)
        }
        // Device-library rows without a serverId column (folders, scan results) are not account data either.
        sql().execSQL("INSERT INTO local_folders (uri, name, addedAt, lastScanAt) VALUES ('content://t', 'Hörbücher', 1, 1)")
        sql().execSQL("INSERT INTO local_titles (id, folderUri, groupKey, relativePath, signature, scannedAt) VALUES ('local:x', 'content://t', 'g', 'p', 's', 1)")

        data.purge(a)

        tables.forEach { t ->
            assertEquals("$t of the signed-out server", 0, count(t, a))
            assertEquals("$t of another server", 1, count(t, b))
            assertEquals("$t of the device library", 1, count(t, dev))
        }
        sql().query("SELECT COUNT(*) FROM local_folders").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        sql().query("SELECT COUNT(*) FROM local_titles").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test fun purge_deletesThisServersFiles_partialDownloadsAndCovers_only() = runBlocking {
        val mine = File(root, "$a/book1").apply { mkdirs() }
        File(mine, "0.mp3").writeBytes(ByteArray(10))
        File(mine, "1.mp3.part").writeBytes(ByteArray(5))
        File(mine, DownloadFiles.COVER).writeBytes(ByteArray(3))
        val other = File(root, "$b/book2").apply { mkdirs() }
        File(other, "0.mp3").writeBytes(ByteArray(7))
        val deviceCover = File(root.parentFile, "device_covers/abc.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2)) }

        assertEquals(18L, data.downloadBytes(a))
        data.purge(a)

        assertFalse(File(root, a).exists())
        assertTrue(File(other, "0.mp3").isFile)
        assertTrue(deviceCover.isFile)
        assertEquals(0L, data.downloadBytes(a))
        assertEquals(7L, data.downloadBytes(b))
        assertEquals(listOf(a), forgottenCovers)
    }

    @Test fun purge_forgetsOnlyThisServersSyncStamps() = runBlocking {
        stamps.set("progress_sync:$a", 1)
        stamps.set("collections:$a", 2)
        stamps.set("progress_sync:$b", 3)
        stamps.set("local_folders_scan", 4)

        data.purge(a)

        assertEquals(null, stamps.get("progress_sync:$a"))
        assertEquals(null, stamps.get("collections:$a"))
        assertEquals(3L, stamps.get("progress_sync:$b"))
        assertEquals(4L, stamps.get("local_folders_scan"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun purge_neverTheDeviceLibrary(): Unit = runBlocking { data.purge(dev) }

    @Test fun downloadBytes_invalidOrMissing_isZero() = runBlocking {
        assertEquals(0L, data.downloadBytes(a))
        assertEquals(0L, data.downloadBytes(dev))
        assertEquals(0L, data.downloadBytes("../x"))
    }

    @Test fun unsynced_countsWhatTheSyncWouldUpload() = runBlocking {
        val progress = db.progressDao()
        progress.saveLocal(a, "book1", 10.0, 100.0, false, 5) // dirty
        progress.replaceFromServer(ProgressEntity(a, "book2", 1.0, 100.0, false, 5)) // synced
        progress.saveLocal(a, "${DeviceLibrary.ID_PREFIX}x", 1.0, 10.0, false, 5) // never sent
        progress.saveLocal(b, "book1", 10.0, 100.0, false, 5) // another server
        db.sessionDao().upsert(LocalSessionEntity("s1", a, "book1", 0.0, 10.0, 10.0, 1, 2))
        db.sessionDao().upsert(LocalSessionEntity("s2", a, "book1", 0.0, 10.0, 10.0, 1, 2, synced = true))
        db.sessionDao().upsert(LocalSessionEntity("s3", a, "${DeviceLibrary.ID_PREFIX}x", 0.0, 1.0, 1.0, 1, 2))
        val bm = db.bookmarkDao()
        bm.add(a, "book1", 30.0, "note", 1) // UPSERT
        bm.add(a, "pod~ep1", 30.0, null, 1) // episode bookmarks never leave the device
        bm.add(a, "book1", 60.0, null, 1)
        sql().execSQL("UPDATE bookmarks SET pending = 'DELETE', onServer = 1 WHERE timeSec = 60.0")
        bm.add(a, "book1", 90.0, null, 1)
        sql().execSQL("UPDATE bookmarks SET pending = 'NONE', onServer = 1 WHERE timeSec = 90.0")

        // 1 progress row + 1 session + 2 bookmark changes
        assertEquals(4, data.unsynced(a))
        assertEquals(1, data.unsynced(b))
        assertEquals(0, data.unsynced(dev))
    }

    @Test fun pauseDownloads_pausesQueuedAndRunning_ofThisServerOnly() = runBlocking {
        val d = db.downloadDao()
        d.upsert(DownloadEntity(a, "q", DownloadState.QUEUED, 1))
        d.upsert(DownloadEntity(a, "r", DownloadState.RUNNING, 1))
        d.upsert(DownloadEntity(a, "c", DownloadState.COMPLETE, 1))
        d.upsert(DownloadEntity(a, "f", DownloadState.FAILED, 1, "http_500"))
        d.upsert(DownloadEntity(b, "q", DownloadState.QUEUED, 1))

        data.pauseDownloads(a)

        assertEquals(DownloadState.PAUSED, d.get(a, "q")!!.state)
        assertEquals(DownloadState.PAUSED, d.get(a, "r")!!.state)
        assertEquals(DownloadState.COMPLETE, d.get(a, "c")!!.state)
        assertEquals(DownloadState.FAILED, d.get(a, "f")!!.state)
        assertEquals(DownloadState.QUEUED, d.get(b, "q")!!.state)
    }

    @Test fun keptBookmarkRows_areUntouchedByCounting() = runBlocking {
        db.bookmarkDao().add(a, "book1", 30.0, null, 1)
        data.unsynced(a)
        assertEquals(BookmarkPending.UPSERT, db.bookmarkDao().all(a).single().pending)
        assertEquals(listOf<BookmarkEntity>(), db.bookmarkDao().all(b))
    }
}
