package io.wolftown.lauschig

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LauncherStartTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test fun onlyAFreshLauncherIntent_counts() {
        assertTrue(isLauncherStart(launcher(), recreated = false))
        assertFalse(isLauncherStart(launcher(), recreated = true))
        assertFalse(isLauncherStart(launcher().putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_CLEANUP), recreated = false))
        assertFalse(isLauncherStart(launcher().setData(Uri.parse("lauschig://oauth?code=x")), recreated = false))
        assertFalse(isLauncherStart(Intent(Intent.ACTION_VIEW), recreated = false))
        assertFalse(isLauncherStart(Intent(Intent.ACTION_MAIN), recreated = false))
        assertFalse(isLauncherStart(null, recreated = false))
    }
}
