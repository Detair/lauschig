package io.wolftown.lauschig.download

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Which volumes count, how they are labelled, and when the chosen one is used. */
@RunWith(RobolectricTestRunner::class)
class StorageVolumesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val base = File(System.getProperty("java.io.tmpdir"), "sv-${System.nanoTime()}")
    private val internal = File(base, "data/files")
    private val primary = File(base, "emulated/0/Android/data/pkg/files").apply { mkdirs() }
    private val sd = File(base, "1A2B-3C4D/Android/data/pkg/files").apply { mkdirs() }
    private val usb = File(base, "USB1/Android/data/pkg/files").apply { mkdirs() }

    private class Probe : VolumeProbe {
        val states = mutableMapOf<String, String>()
        val emulated = mutableSetOf<String>()
        val removable = mutableSetOf<String>()
        val labels = mutableMapOf<String, String>()
        override fun state(dir: File) = states[dir.path] ?: Environment.MEDIA_MOUNTED
        override fun isEmulated(dir: File) = dir.path in emulated
        override fun isRemovable(dir: File) = dir.path in removable
        override fun description(dir: File) = labels[dir.path]
    }

    private val probe = Probe().apply {
        emulated += primary.path
        removable += sd.path
        labels[sd.path] = "SanDisk SD card"
    }
    private var dirs: List<File?> = listOf(primary, sd)
    private var now = 0L
    private var scans = 0
    private fun volumes() = StorageVolumes(internal, { scans++; dirs }, probe, clock = { now }, ttlMs = 10_000)

    @After fun tearDown() { base.deleteRecursively() }

    @Test fun theEmulatedPrimaryStorage_isNoChoice_theCardIs() {
        val v = volumes()
        assertEquals(VolumeKind.INTERNAL, v.internal().kind)
        assertEquals(File(internal, "downloads"), v.internal().downloadRoot)
        val ext = v.external().single()
        assertEquals(sd, ext.path)
        assertEquals(VolumeKind.SD_CARD, ext.kind)
        assertEquals("SanDisk SD card", ext.label)
        assertTrue(ext.writable)
        assertEquals(File(sd, "downloads"), ext.downloadRoot)
        assertEquals(listOf(VolumeKind.INTERNAL, VolumeKind.SD_CARD), v.all().map { it.kind })
    }

    @Test fun anUnavailableCard_isLeftOut_aReadOnlyOneIsListedButNotWritable() {
        // getExternalFilesDirs gives null for a volume that is not available.
        dirs = listOf(primary, null, sd, usb)
        probe.states[usb.path] = Environment.MEDIA_UNMOUNTED
        assertEquals(listOf(sd), volumes().external().map { it.path })
        probe.states[sd.path] = Environment.MEDIA_MOUNTED_READ_ONLY
        val ro = volumes().external().single()
        assertFalse(ro.writable)
    }

    @Test fun aNonRemovableOtherVolume_isExternalStorage_withoutALabel() {
        dirs = listOf(primary, usb)
        val v = volumes().external().single()
        assertEquals(VolumeKind.EXTERNAL, v.kind)
        assertNull(v.label)
    }

    @Test fun theListIsCachedForAWhile_andReadAnewAfterARefresh() {
        val v = volumes()
        v.external(); v.external()
        assertEquals(1, scans)
        now = 10_001
        v.external()
        assertEquals(2, scans)
        v.refresh()
        v.external()
        assertEquals(3, scans)
    }

    @Test fun aFailingStorageService_meansInternalOnly() {
        val v = StorageVolumes(internal, { error("binder") }, probe)
        assertEquals(emptyList<DownloadVolume>(), v.external())
        assertEquals(1, v.all().size)
    }

    @Test fun target_isTheChosenVolume_onlyWhileItIsThereAndWritable_withANoMediaFile() {
        var chosen: String? = sd.path
        val roots = VolumeDownloadRoots(volumes(), { chosen })
        assertEquals(File(sd, "downloads"), roots.target())
        assertTrue("music apps must not list the audiobooks", File(sd, "downloads/.nomedia").isFile)
        assertEquals(listOf(File(sd, "downloads")), roots.external())
        chosen = null
        assertNull(roots.target())
        chosen = "/storage/9999-0000/Android/data/pkg/files" // a card that is gone
        assertNull(roots.target())
        chosen = sd.path
        probe.states[sd.path] = Environment.MEDIA_MOUNTED_READ_ONLY
        assertNull(VolumeDownloadRoots(volumes(), { chosen }).target())
    }

    @Test fun storagePath_defaultsToInternal_andIsStoredAndCleared() = runBlocking {
        val settings = DownloadSettings(ctx)
        assertNull(settings.storagePath.first())
        settings.setStoragePath(sd.path)
        assertEquals(sd.path, settings.storagePath.first())
        settings.setStoragePath(null)
        assertNull(settings.storagePath.first())
        settings.setStoragePath(" ")
        assertNull(settings.storagePath.first())
    }

    @Test fun theStoredChoice_isWhatTheRootsUse_onceLoaded() = runBlocking {
        val settings = DownloadSettings(ctx)
        settings.setStoragePath(sd.path)
        val v = StorageVolumes(internal, { listOf(primary, sd) }, probe)
        val roots = VolumeDownloadRoots(v, settings)
        roots.awaitReady()
        assertEquals(File(sd, "downloads"), roots.target())
        settings.setStoragePath(null)
    }

    @Test fun aStoreThatFails_inAnyWay_neverLetsADownloadWait_itGoesInside() = runBlocking {
        for (failure in listOf(java.io.IOException("corrupt"), IllegalStateException("odd"), SecurityException("denied"))) {
            val roots = VolumeDownloadRoots(volumes(), kotlinx.coroutines.flow.flow<String?> { throw failure })
            kotlinx.coroutines.withTimeout(5_000) { roots.awaitReady() }
            assertNull(roots.target())
        }
        // A store that never answers is the only wait: it is not a failure (DataStore answers or throws).
        val slow = VolumeDownloadRoots(volumes(), kotlinx.coroutines.flow.flow<String?> { kotlinx.coroutines.delay(50); emit(sd.path) })
        kotlinx.coroutines.withTimeout(5_000) { slow.awaitReady() }
        assertEquals(File(sd, "downloads"), slow.target())
    }
}
