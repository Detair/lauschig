package io.wolftown.lauschig.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A folder tree in memory: directories by document id. */
class FakeTree : DocumentTree {
    val children = HashMap<String, MutableList<DocEntry>>()
    val roots = HashMap<String, DocEntry>()
    val access = HashMap<String, TreeAccess>()
    val bytes = HashMap<String, ByteArray>()
    /** Directories (document ids) whose listing fails. */
    val broken = HashSet<String>()
    var rootThrows = false

    fun root(tree: String, name: String): DocEntry = DocEntry("$tree/", name, isDirectory = true).also { roots[tree] = it; children[it.documentId] = mutableListOf() }
    fun dir(parent: DocEntry, name: String): DocEntry = DocEntry("${parent.documentId}$name/", name, isDirectory = true).also {
        children.getValue(parent.documentId) += it
        children[it.documentId] = mutableListOf()
    }
    fun file(parent: DocEntry, name: String, size: Long = 100, mtime: Long = 1): DocEntry =
        DocEntry("${parent.documentId}$name", name, isDirectory = false, size = size, lastModified = mtime).also { children.getValue(parent.documentId) += it }
    fun remove(parent: DocEntry, name: String) { children.getValue(parent.documentId).removeAll { it.name == name } }

    override fun access(treeUri: String): TreeAccess = access[treeUri] ?: if (treeUri in roots) TreeAccess.OK else TreeAccess.MISSING
    override fun root(treeUri: String): DocEntry? = if (rootThrows) throw IllegalStateException("provider crashed") else roots[treeUri]
    override fun children(treeUri: String, dir: DocEntry): List<DocEntry>? = if (dir.documentId in broken) null else children[dir.documentId]?.toList()
    override fun documentUri(treeUri: String, documentId: String): String = "content://fake/$documentId"
    override fun readBytes(uri: String, maxBytes: Int): ByteArray? = bytes[uri]
}

class FakeProbe : AudioProbe {
    val tags = HashMap<String, AudioTags>()
    val pictures = HashMap<String, ByteArray>()
    val reads = mutableListOf<String>()
    val unreadable = HashSet<String>()
    val throwing = HashSet<String>()
    override fun tags(uri: String): AudioTags? {
        reads += uri
        if (uri in throwing) throw IllegalStateException("decoder crashed")
        if (uri in unreadable) return null
        return tags[uri] ?: AudioTags(durationMs = 60_000)
    }
    override fun embeddedPicture(uri: String): ByteArray? = pictures[uri]
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalLibraryScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val tree = FakeTree()
    private val probe = FakeProbe()
    private lateinit var covers: LocalCovers
    private var now = 1_000L
    private lateinit var scanner: LocalLibraryScanner
    private val uri = "content://tree/books"
    private val dev = DeviceLibrary.SERVER_ID

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
        covers = LocalCovers(tmp.newFolder("covers"))
        scanner = LocalLibraryScanner(tree, probe, db.localLibraryDao(), covers, { now })
    }

    @After fun tearDown() = db.close()

    private suspend fun addFolder() = db.localLibraryDao().addFolder(LocalFolderEntity(uri, "Hörbücher", addedAt = 1))

    private fun bookDir(): DocEntry {
        val root = tree.root(uri, "Hörbücher")
        val book = tree.dir(root, "Ende - Momo")
        tree.file(book, "02.mp3", size = 200)
        tree.file(book, "01.mp3", size = 100)
        tree.file(book, "cover.jpg")
        return book
    }

    private suspend fun onlyItem(): ItemEntity = db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).single()

    @Test fun newFolder_createsTheTitleUnderTheDeviceServer_withContentPathsAndChapters() = runTest {
        val book = bookDir()
        tree.bytes["content://fake/${book.documentId}cover.jpg"] = byteArrayOf(1, 2, 3)
        addFolder()
        scanner.scanAll()
        val item = onlyItem()
        assertTrue(DeviceLibrary.isValidId(item.id))
        assertEquals(DeviceLibrary.titleId(uri, "dir:${book.documentId}"), item.id)
        assertEquals("Momo", item.title)
        assertEquals("Ende", item.author)
        assertEquals(120.0, item.durationSec, 0.0)
        assertTrue(item.detailsLoaded)
        val files = db.audioFileDao().forItem(dev, item.id)
        assertEquals(listOf("content://fake/${book.documentId}01.mp3", "content://fake/${book.documentId}02.mp3"), files.map { it.localPath })
        assertEquals(listOf(1, 2), files.map { it.index })
        assertEquals(listOf(100L, 200L), files.map { it.completedBytes })
        assertEquals(listOf("01", "02"), db.chapterDao().forItem(dev, item.id).map { it.title })
        assertArrayEqualsCover(byteArrayOf(1, 2, 3), item.id)
        val folder = db.localLibraryDao().observeFolders().first().single()
        assertEquals(1, folder.titles)
        assertNull(folder.error)
        assertEquals(now, folder.lastScanAt)
    }

    private fun assertArrayEqualsCover(expected: ByteArray, id: String) {
        val f = covers.file(id)!!
        assertTrue(f.isFile)
        assertTrue(expected.contentEquals(f.readBytes()))
    }

    @Test fun embeddedPicture_winsOverTheCoverFile_andNoCoverMeansNoFile() = runTest {
        val book = bookDir()
        probe.pictures["content://fake/${book.documentId}01.mp3"] = byteArrayOf(9)
        tree.bytes["content://fake/${book.documentId}cover.jpg"] = byteArrayOf(1)
        val root = tree.roots.getValue(uri)
        val bare = tree.dir(root, "Ohne Bild")
        tree.file(bare, "1.mp3")
        addFolder()
        scanner.scanAll()
        val items = db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).associateBy { it.title }
        assertArrayEqualsCover(byteArrayOf(9), items.getValue("Momo").id)
        assertFalse(covers.file(items.getValue("Ohne Bild").id)!!.exists())
    }

    @Test fun unchangedFiles_areNotReadAgain() = runTest {
        bookDir()
        addFolder()
        scanner.scanAll()
        val first = probe.reads.size
        now = 2_000
        scanner.scanAll()
        assertEquals(first, probe.reads.size)
        assertEquals(1_000L, onlyItem().updatedAt)
    }

    @Test fun addedFile_rebuildsFilesAndChapters_keepingAddedAtAndProgress() = runTest {
        val book = bookDir()
        addFolder()
        scanner.scanAll()
        val id = onlyItem().id
        db.progressDao().saveLocal(dev, id, 70.0, 120.0, false, 5)
        now = 2_000
        tree.file(book, "03.mp3")
        scanner.scanAll()
        assertEquals(listOf(1, 2, 3), db.audioFileDao().forItem(dev, id).map { it.index })
        assertEquals(3, db.chapterDao().forItem(dev, id).size)
        assertEquals(180.0, onlyItem().durationSec, 0.0)
        assertEquals(1_000L, onlyItem().addedAt)
        assertEquals(70.0, db.progressDao().get(dev, id)!!.currentTimeSec, 0.0)
    }

    @Test fun removedFile_isDroppedOnRescan() = runTest {
        val book = bookDir()
        addFolder()
        scanner.scanAll()
        tree.remove(book, "02.mp3")
        scanner.scanAll()
        val id = onlyItem().id
        assertEquals(listOf("content://fake/${book.documentId}01.mp3"), db.audioFileDao().forItem(dev, id).map { it.localPath })
        assertTrue(db.chapterDao().forItem(dev, id).isEmpty())
    }

    @Test fun vanishedTitle_isRemoved_butItsProgressAndBookmarksStay() = runTest {
        bookDir()
        addFolder()
        scanner.scanAll()
        val id = onlyItem().id
        db.progressDao().saveLocal(dev, id, 70.0, 120.0, false, 5)
        db.bookmarkDao().save(dev, id, 10.0, "n", now = 1)
        tree.children.getValue(tree.roots.getValue(uri).documentId).clear()
        scanner.scanAll()
        assertNull(db.itemDao().get(dev, id))
        assertTrue(db.audioFileDao().forItem(dev, id).isEmpty())
        assertTrue(db.localLibraryDao().titlesIn(uri).isEmpty())
        assertNotNull(db.progressDao().get(dev, id))
        assertEquals(1, db.bookmarkDao().observeForKey(dev, id).first().size)
        assertFalse(covers.file(id)!!.exists())
    }

    @Test fun revokedPermissionOrMissingFolder_keepsTheTitles_andRecordsWhy() = runTest {
        bookDir()
        addFolder()
        scanner.scanAll()
        tree.access[uri] = TreeAccess.NO_ACCESS
        scanner.scanAll()
        assertEquals(1, db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).size)
        assertEquals(LocalFolderEntity.ERROR_NO_ACCESS, db.localLibraryDao().folders().single().error)
        tree.access[uri] = TreeAccess.MISSING
        scanner.scanAll()
        assertEquals(1, db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).size)
        assertEquals(LocalFolderEntity.ERROR_MISSING, db.localLibraryDao().folders().single().error)
        tree.access.remove(uri)
        scanner.scanAll()
        assertNull(db.localLibraryDao().folders().single().error)
    }

    @Test fun unreadableFiles_giveNoTitle() = runTest {
        val root = tree.root(uri, "Hörbücher")
        val bad = tree.dir(root, "Kaputt")
        val f = tree.file(bad, "1.mp3")
        probe.tags["content://fake/${f.documentId}"] = AudioTags(durationMs = null)
        addFolder()
        scanner.scanAll()
        assertTrue(db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).isEmpty())
    }

    @Test fun removeFolder_deletesItsTitles_keepsProgress_andOtherServersAreUntouched() = runTest {
        bookDir()
        addFolder()
        db.itemDao().upsertDetails(ItemEntity("srv0000000000000", "li_1", DeviceLibrary.LIBRARY_ID, "Server-Titel"))
        scanner.scanAll()
        val id = onlyItem().id
        db.progressDao().saveLocal(dev, id, 1.0, 120.0, false, 5)
        val removed = scanner.removeFolder(uri)
        assertEquals(listOf(id), removed)
        assertTrue(db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).isEmpty())
        assertTrue(db.localLibraryDao().folders().isEmpty())
        assertNotNull(db.progressDao().get(dev, id))
        assertNotNull(db.itemDao().get("srv0000000000000", "li_1"))
        assertFalse(covers.file(id)!!.exists())
    }

    @Test fun twoFolders_areScannedIndependently() = runTest {
        bookDir()
        val other = "content://tree/other"
        val r = tree.root(other, "Hörspiele")
        tree.file(r, "Folge 1.m4b")
        addFolder()
        db.localLibraryDao().addFolder(LocalFolderEntity(other, "Hörspiele", addedAt = 2))
        scanner.scanAll()
        assertEquals(setOf("Momo", "Folge 1"), db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).map { it.title }.toSet())
        assertEquals(1, db.localLibraryDao().titlesIn(other).size)
    }
    // --- An incomplete or failing scan never deletes titles -------------------------------------------------------

    private fun twoBooks(): Pair<DocEntry, DocEntry> {
        val root = tree.root(uri, "Hörbücher")
        val a = tree.dir(root, "A")
        tree.file(a, "1.mp3")
        val b = tree.dir(root, "B")
        tree.file(b, "1.mp3")
        return a to b
    }

    private suspend fun titles() = db.itemDao().getByLibrary(dev, DeviceLibrary.LIBRARY_ID).map { it.title }.toSet()

    @Test fun subfolderThatCannotBeListed_keepsItsTitle_andRecordsAScanError() = runTest {
        val (_, b) = twoBooks()
        addFolder()
        scanner.scanAll()
        tree.broken += b.documentId
        val a2 = tree.dir(tree.roots.getValue(uri), "C")
        tree.file(a2, "1.mp3")
        scanner.scanAll()
        assertEquals(setOf("A", "B", "C"), titles())
        assertEquals(LocalFolderEntity.ERROR_SCAN, db.localLibraryDao().folders().single().error)
        // Readable again: the error goes, nothing was lost.
        tree.broken.clear()
        scanner.scanAll()
        assertNull(db.localLibraryDao().folders().single().error)
        assertEquals(setOf("A", "B", "C"), titles())
    }

    @Test fun listedTitleWhoseFilesCannotBeReadNow_isKept() = runTest {
        val (a, _) = twoBooks()
        addFolder()
        scanner.scanAll()
        tree.remove(a, "1.mp3")
        val f = tree.file(a, "1.mp3", mtime = 99) // changed: read again, but unreadable this time
        probe.unreadable += "content://fake/${f.documentId}"
        scanner.scanAll()
        assertEquals(setOf("A", "B"), titles())
    }

    @Test fun oneTitleThatThrows_doesNotStopTheOthers_andGivesAGenericError() = runTest {
        val (a, b) = twoBooks()
        addFolder()
        scanner.scanAll()
        tree.remove(a, "1.mp3")
        val f = tree.file(a, "1.mp3", mtime = 99)
        probe.throwing += "content://fake/${f.documentId}"
        val c = tree.dir(tree.roots.getValue(uri), "C")
        tree.file(c, "1.mp3")
        tree.children.getValue(tree.roots.getValue(uri).documentId).remove(b) // B vanished: listing is complete, so it goes
        scanner.scanAll()
        assertEquals(setOf("A", "C"), titles())
        assertEquals(LocalFolderEntity.ERROR_SCAN, db.localLibraryDao().folders().single().error)
    }

    @Test fun depthOrEntryCaps_keepTheTitlesBeyondThem() = runTest {
        val root = tree.root(uri, "Hörbücher")
        val deep = tree.dir(tree.dir(root, "Reihe"), "Band 1")
        tree.file(deep, "1.mp3")
        tree.file(tree.dir(root, "Flach"), "1.mp3")
        addFolder()
        scanner.scanAll()
        assertEquals(setOf("Band 1", "Flach"), titles())
        LocalLibraryScanner(tree, probe, db.localLibraryDao(), covers, { now }, maxDepth = 1).scanAll()
        assertEquals(setOf("Band 1", "Flach"), titles())
        assertEquals(LocalFolderEntity.ERROR_SCAN, db.localLibraryDao().folders().single().error)
        LocalLibraryScanner(tree, probe, db.localLibraryDao(), covers, { now }, maxEntries = 1).scanAll()
        assertEquals(setOf("Band 1", "Flach"), titles())
    }

    @Test fun aProviderThatThrows_keepsTheTitles_withAGenericErrorNotMissing() = runTest {
        twoBooks()
        addFolder()
        scanner.scanAll()
        tree.rootThrows = true
        scanner.scanAll()
        assertEquals(setOf("A", "B"), titles())
        assertEquals(LocalFolderEntity.ERROR_SCAN, db.localLibraryDao().folders().single().error)
    }
}
