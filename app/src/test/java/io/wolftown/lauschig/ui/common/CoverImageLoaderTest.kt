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
}
