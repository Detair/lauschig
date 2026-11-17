package io.wolftown.lauschig.tv

import android.content.Context
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvManifestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launcherActivities(category: String): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), 0)
            .map { it.activityInfo.name }

    @Test fun theTvLauncher_opensTheTvActivity() =
        assertEquals(listOf(TvActivity::class.java.name), launcherActivities(Intent.CATEGORY_LEANBACK_LAUNCHER))

    @Test fun thePhoneLauncher_stillOpensTheMainActivity() =
        assertEquals(listOf(MainActivity::class.java.name), launcherActivities(Intent.CATEGORY_LAUNCHER))

    @Test fun neitherTouchscreenNorLeanback_isRequired() {
        @Suppress("DEPRECATION")
        val features = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        for (name in listOf("android.hardware.touchscreen", PackageManager.FEATURE_LEANBACK)) {
            val feature = features.firstOrNull { it.name == name }
            assertNotNull("$name is declared", feature)
            assertEquals("$name is optional", 0, feature!!.flags and FeatureInfo.FLAG_REQUIRED)
        }
    }

    @Test fun theAppHasABanner() = assertNotEquals(0, context.applicationInfo.banner)

    /**
     * The TV may dim and sleep like any other screen (the player's own "screen off" is drawn, not a window flag). A window
     * flag cannot be read without starting the Hilt activity, so the TV sources are searched for both ways to set it.
     */
    @Test fun theTvInterface_neverKeepsTheScreenOn() {
        val sources = File("src/main/java/io/wolftown/lauschig/tv")
        assertTrue("TV sources found at ${sources.absolutePath}", sources.isDirectory)
        val offenders = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().let { "FLAG_KEEP_SCREEN_ON" in it || "keepScreenOn" in it } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
