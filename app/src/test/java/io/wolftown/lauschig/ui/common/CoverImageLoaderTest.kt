package io.wolftown.lauschig.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoverImageLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun loader() = newCoverImageLoader(
        context, ApiProvider(flowOf(ConnectionState.Offline), OkHttpClient(), FakeAccountStore(), CoroutineScope(Dispatchers.Unconfined)),
    )

    @Test fun loader_hasExplicit150MbDiskCache_inCoverDir() {
        val cache = loader().diskCache
        assertNotNull(cache)
        assertEquals(150L * 1024 * 1024, cache!!.maxSize)
        assertEquals(context.cacheDir.resolve("coil_covers").absolutePath, cache.directory.toString())
    }

    @Test fun diskCache_survivesANewLoader_andSignOutClearsIt() {
        val first = loader().diskCache!!
        first.openEditor("cover-key")!!.apply {
            first.fileSystem.write(data) { writeUtf8("jpeg") }
            commit()
        }
        first.openSnapshot("cover-key")!!.close()
        // "App restart": a fresh loader on the same directory still has the cover.
        val second = loader().diskCache!!
        assertNotNull(second.openSnapshot("cover-key"))
        // Sign-out path (AccountStateCleaner): diskCache.clear().
        second.clear()
        assertNull(loader().diskCache!!.openSnapshot("cover-key"))
    }

    @Test fun legacyCoilCacheDirs_areDeleted_missingOnesAreFine_coverCacheIsUntouched() {
        deleteLegacyCoverCaches(context) // nothing there: no failure
        val legacy = listOf("coil3_disk_cache", "image_cache").map { context.cacheDir.resolve(it).apply { mkdirs(); resolve("c.1").writeText("x") } }
        val covers = context.cacheDir.resolve("coil_covers").apply { mkdirs(); resolve("keep").writeText("x") }
        deleteLegacyCoverCaches(context)
        legacy.forEach { assertEquals(it.path, false, it.exists()) }
        assertEquals(true, covers.resolve("keep").exists())
        deleteLegacyCoverCaches(context) // idempotent
    }
}
