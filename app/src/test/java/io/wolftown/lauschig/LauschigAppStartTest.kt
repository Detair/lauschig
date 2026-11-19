package io.wolftown.lauschig

import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.cast.DataStoreCastSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real application, with its whole Hilt graph. Its start leaves coroutines running that resume on `Dispatchers.Main` (see
 * robolectric.properties), so this class gets a sandbox of its own: another `instrumentedPackages` means another class loader,
 * and so another `Dispatchers.Main` than the plain tests race with `setMain`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LauschigApp::class, instrumentedPackages = ["io.wolftown.lauschig.appstart"])
class LauschigAppStartTest {
    /** A session a dead process left noted for an account that is gone: the start forgets it, the server closes it by itself. */
    @Test fun theStart_dealsWithWhatTheLastProcessLeftOpen() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LauschigApp>()
        val store = DataStoreCastSessionStore(app)
        store.add("gone", "left-open")
        assertEquals(listOf("gone" to "left-open"), store.all())
        app.onCreate()
        repeat(200) {
            if (store.all().isEmpty()) return@runBlocking
            delay(25)
        }
        assertEquals(emptyList<Pair<String, String>>(), store.all())
    }
}
