package io.wolftown.lauschig.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** Downloads on several volumes: where a title goes, where it is found, and that every walk covers every volume. */
@RunWith(RobolectricTestRunner::class)
class DownloadFilesTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var base: File
    private lateinit var internal: File
    private lateinit var card: File
    private val roots = FakeRoots()

    class FakeRoots(var ext: List<File> = emptyList(), var tgt: File? = null) : DownloadRoots {
        override fun external() = ext
        override fun target() = tgt
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        base = File(ctx.cacheDir, "df-${System.nanoTime()}")
        internal = File(base, "internal/downloads").apply { mkdirs() }
        card = File(base, "card/Android/data/pkg/files/downloads").apply { mkdirs() }
        roots.ext = listOf(card)
    }

    @After fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun files(space: (File) -> Long = { 0L }) = DownloadFiles(internal, db.audioFileDao(), usableSpace = space, roots = roots)

    private fun write(root: File, item: String, name: String, bytes: Int = 3): File =
        File(File(File(root, s), item), name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test fun aNewTitle_goesToTheChosenVolume_withoutOneToTheInternalStorage() {
        roots.tgt = card
        assertEquals(File(File(card, s), "b1"), files().itemDir(s, "b1"))
        assertEquals(File(File(File(card, s), "b1"), "1.mp3"), files().audioFile(s, "b1", 1, "mp3"))
        roots.tgt = null
        assertEquals(File(File(internal, s), "b1"), files().itemDir(s, "b1"))
    }

    @Test fun aTitleStaysWhereItsDirectoryIs_whateverTheChoiceIsNow() {
        write(card, "onCard", "1.mp3")
        write(internal, "inside", "1.mp3")
        roots.tgt = null
        assertEquals(File(File(card, s), "onCard"), files().itemDir(s, "onCard"))
        assertEquals(File(File(card, s), "onCard/cover.jpg"), files().coverFile(s, "onCard"))
        roots.tgt = card
        assertEquals(File(File(internal, s), "inside"), files().itemDir(s, "inside"))
    }

    @Test fun aTitleOnTwoVolumes_usesTheInternalCopy() {
        write(card, "b1", "1.mp3")
        write(internal, "b1", "1.mp3")
        roots.tgt = card
        assertEquals(File(File(internal, s), "b1"), files().itemDir(s, "b1"))
        assertTrue(files().isOnSeveralVolumes(s, "b1"))
        assertFalse(files().isOnSeveralVolumes(s, "other"))
    }

    @Test fun aCardThatIsOut_isNotSearched_andNewTitlesGoInside() {
        write(card, "b1", "1.mp3")
        roots.ext = emptyList() // unmounted: the system no longer lists it
        roots.tgt = null
        assertEquals(File(File(internal, s), "b1"), files().itemDir(s, "b1"))
        assertEquals(emptyList<String>(), files().serverIds())
    }

    @Test fun serverIds_sizes_andDeletion_coverEveryVolume() {
        val other = "bbbbbbbbbbbbbbbb"
        write(internal, "b1", "1.mp3", 10)
        write(card, "b2", "1.mp3", 20)
        write(card, "b2", "1.mp3.part", 5)
        File(File(card, other), "x").mkdirs()
        File(card, VolumeDownloadRoots.NO_MEDIA).createNewFile()
        File(card, "not-a-server").mkdirs()
        val f = files()
        assertEquals(listOf(s, other), f.serverIds())
        assertEquals(35L, f.serverBytes(s))
        assertTrue(f.deleteServer(s))
        assertFalse(File(internal, s).exists())
        assertFalse(File(card, s).exists())
        assertTrue("another server's downloads stay", File(card, other).isDirectory)
        assertTrue(File(card, VolumeDownloadRoots.NO_MEDIA).isFile)
        assertEquals(0L, f.serverBytes(s))
    }

    @Test fun itemIds_listEveryVolume_onlyValidIds() {
        write(internal, "b1", "1.mp3")
        write(card, "b2", "1.mp3")
        write(card, "b1", "2.mp3")
        File(File(card, s), "bad id!").mkdirs()
        assertEquals(listOf("b1", "b2"), files().itemIds(s))
        assertEquals(emptyList<String>(), files().itemIds("../etc"))
    }

    @Test fun deleteItem_removesTheTitleOnEveryVolume_andNothingElse() {
        write(internal, "b1", "1.mp3")
        write(card, "b1", "1.mp3")
        write(card, "b2", "1.mp3")
        files().deleteItem(s, "b1")
        assertFalse(File(File(internal, s), "b1").exists())
        assertFalse(File(File(card, s), "b1").exists())
        assertTrue(File(File(card, s), "b2").isDirectory)
    }

    @Test fun cleanupOrphans_dropsTheStaleCopyOnTheOtherVolume_keepsReferencedFilesAndTheCover() = runBlocking {
        val kept = write(internal, "b1", "1.mp3")
        write(internal, "b1", DownloadFiles.COVER)
        write(internal, "b1", "2.mp3.part")
        write(card, "b1", "1.mp3.part")
        write(card, "b1", DownloadFiles.COVER)
        db.audioFileDao().replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "ino1", 1.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, "b1", 1, kept.absolutePath, 3)
        files().cleanupOrphans(s, "b1")
        assertEquals(setOf("1.mp3", DownloadFiles.COVER), File(File(internal, s), "b1").list()!!.toSet())
        assertFalse("the emptied copy on the card goes", File(File(card, s), "b1").exists())
    }

    @Test fun cleanupOrphans_keepsFilesOnTheCardThatAreStillReferenced() = runBlocking {
        val onCard = write(card, "b1", "1.mp3")
        write(internal, "b1", "2.mp3")
        db.audioFileDao().replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "ino1", 1.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, "b1", 1, onCard.absolutePath, 3)
        files().cleanupOrphans(s, "b1")
        assertTrue(onCard.isFile)
        assertFalse(File(File(internal, s), "b1/2.mp3").exists())
    }

    @Test fun freeSpace_isTheChosenVolumes() {
        roots.tgt = card
        val asked = mutableListOf<File>()
        files { asked += it; 42L }.freeSpace()
        assertEquals(listOf(card), asked)
    }

    @Test fun aLinkOutOfTheCardsRoot_isRefused() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(card, s).mkdirs()
        Files.createSymbolicLink(File(File(card, s), "evil").toPath(), outside.toPath())
        try {
            files().itemDir(s, "evil")
            fail("a directory that resolves outside every root must be refused")
        } catch (e: FatalDownloadException) {
            assertEquals("invalid_path", e.code)
        }
        files().deleteItem(s, "evil") // refused quietly, nothing outside is touched
        assertTrue(outside.isDirectory)
    }

    @Test fun tidy_deletesTitlesWithoutARow_andStaleCopiesOfCompleteOnes_neverLiveDownloads() = runBlocking {
        // Deleted while the card was out: the directory came back, the row is gone.
        write(card, "gone", "1.mp3")
        // Complete inside, a stale partial copy on the card.
        val done = write(internal, "done", "1.mp3")
        write(card, "done", "1.mp3.part")
        // Still running: both copies stay (its .part files are live).
        write(internal, "running", "1.mp3.part")
        write(card, "running", "1.mp3.part")
        db.audioFileDao().replaceFromServer(s, "done", listOf(AudioFileEntity(s, "done", 1, "i", 1.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, "done", 1, done.absolutePath, 3)
        db.downloadDao().upsert(DownloadEntity(s, "done", DownloadState.COMPLETE, 1))
        db.downloadDao().upsert(DownloadEntity(s, "running", DownloadState.RUNNING, 1))
        val n = DownloadTidy(files(), db.downloadDao()).tidy(s)
        assertEquals(2, n)
        assertFalse(File(File(card, s), "gone").exists())
        assertFalse(File(File(card, s), "done").exists())
        assertTrue(done.isFile)
        assertTrue(File(File(internal, s), "running/1.mp3.part").isFile)
        assertTrue(File(File(card, s), "running/1.mp3.part").isFile)
    }
}
