package io.wolftown.lauschig.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.content.pm.PackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TvDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test @Config(qualifiers = TV_QUALIFIERS)
    fun aTelevision_isOne() = assertTrue(context.isTelevision())

    @Test
    fun aPhone_isNoTelevision() = assertFalse(context.isTelevision())

    /** A box or projector that runs the TV launcher but reports a normal UI mode. */
    @Test
    fun theTvLauncherFeatureAlone_makesATelevision() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(context.isTelevision())
    }

    @Test @Config(qualifiers = TV_QUALIFIERS)
    fun onATelevision_thePlayerOpensInTheTvActivity() {
        val intent = playerScreenIntent(context)
        assertEquals(TvActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.OPEN_PLAYER, intent.getStringExtra(MainActivity.EXTRA_OPEN))
    }

    @Test
    fun onAPhone_thePlayerOpensInTheMainActivity() {
        val intent = playerScreenIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.OPEN_PLAYER, intent.getStringExtra(MainActivity.EXTRA_OPEN))
    }
}
