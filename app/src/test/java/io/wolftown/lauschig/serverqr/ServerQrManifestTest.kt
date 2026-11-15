package io.wolftown.lauschig.serverqr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the manifest says about the scanner: it may use a camera, needs none, and no link from outside reaches it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ServerQrManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS or PackageManager.GET_ACTIVITIES)

    @Test fun theAppMayAskForTheCamera() {
        assertTrue(Manifest.permission.CAMERA in info.requestedPermissions.orEmpty())
    }

    /**
     * A device without a camera still gets the app (it just has no scan button). The camera permission alone would make
     * Google Play require a camera with autofocus; every camera feature is therefore named as not required.
     */
    @Test fun noCameraIsRequired() {
        val features = info.reqFeatures.orEmpty().filter { it.name != null }.associateBy { it.name }
        for (name in listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus")) {
            val feature = features[name]
            assertTrue("$name is named", feature != null)
            assertEquals("$name is not required", 0, feature!!.flags and FeatureInfo.FLAG_REQUIRED)
        }
    }

    /**
     * Only the scanner inside the app takes a server code. A link `lauschig://server?...` from a web page, a mail or
     * another app's QR reader opens nothing: it could otherwise fill the setup form with a stranger's server.
     */
    @Test fun noLinkFromOutside_opensTheApp_withAServerCode() {
        fun opens(uri: String) = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName), 0)
            .isNotEmpty()
        assertTrue("the control: the SSO return link does open the app", opens("lauschig://oauth?code=abc&state=def"))
        assertFalse(opens("lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org"))
        assertFalse(opens("lauschig://server"))
        assertFalse(opens("lauschig://server/?v=1&local=http%3A%2F%2F192.168.1.20%3A13378"))
    }
}
