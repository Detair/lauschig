package io.wolftown.lauschig.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.download.StorageVolumes
import io.wolftown.lauschig.download.VolumeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DownloadLocationViewModelTest {
    private val viewModels = TestViewModels()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun viewModel_storesTheChoice_andShowsTheVolumes() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val base = File(ctx.cacheDir, "loc-${System.nanoTime()}")
        val card = File(base, "card/files").apply { mkdirs() }
        val probe = object : VolumeProbe {
            override fun state(dir: File) = android.os.Environment.MEDIA_MOUNTED
            override fun isEmulated(dir: File) = false
            override fun isRemovable(dir: File) = true
            override fun description(dir: File) = "Test card"
        }
        val settings = DownloadSettings(ctx)
        val vm = viewModels.own(DownloadLocationViewModel(settings, StorageVolumes(File(base, "files"), { listOf(card) }, probe), Dispatchers.IO))
        try {
            val first = withTimeout(5_000) { vm.state.first { it.loaded } }
            assertEquals(listOf(null, card.absolutePath), first.options.map { it.path })
            assertEquals("Test card", first.options[1].label)
            vm.choose(card.absolutePath)
            withTimeout(5_000) { vm.state.first { it.chosen == card.absolutePath } }
            assertEquals(card.absolutePath, settings.storagePath.first())
        } finally {
            settings.setStoragePath(null)
            base.deleteRecursively()
        }
    }
}
