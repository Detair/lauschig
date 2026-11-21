package io.wolftown.lauschig.tvsetup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only the scanner in „Fernseher einrichten“ reads a TV's code. A link `lauschig://tv?...` from a web page, a mail or
 * another app's QR reader opens nothing: it could otherwise lead the phone to hand a session to a stranger's device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TvSetupManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun opens(uri: String, browsable: Boolean = true): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        if (browsable) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    @Test fun noLinkFromOutside_opensTheApp_withATvCode() {
        assertTrue("the control: the SSO return link does open the app", opens("lauschig://oauth?code=abc&state=def"))
        val code = "lauschig://tv?v=1&a=192.168.1.50&p=41234&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertFalse(opens(code))
        assertFalse(opens(code, browsable = false))
        assertFalse(opens("lauschig://tv"))
        assertFalse(opens("lauschig://tv/?v=1"))
    }
}
