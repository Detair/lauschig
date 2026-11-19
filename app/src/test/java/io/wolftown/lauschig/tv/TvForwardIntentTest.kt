package io.wolftown.lauschig.tv

import android.app.SearchManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.isLauncherStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What MainActivity hands on to the TV interface on a television. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvForwardIntentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun forward(incoming: Intent): Intent = tvForwardIntent(context, incoming).also {
        assertEquals(TvActivity::class.java.name, it.component?.className)
    }

    @Test fun aLauncherStart_staysOne() {
        val incoming = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val tv = forward(incoming)
        assertEquals(Intent.ACTION_MAIN, tv.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), tv.categories)
        assertTrue(isLauncherStart(tv, recreated = false))
        // How the first activity was started is the system's to decide again.
        assertEquals(0, tv.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    @Test fun thePlayerRequest_arrives() {
        val tv = forward(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER))
        assertEquals(MainActivity.OPEN_PLAYER, tv.getStringExtra(MainActivity.EXTRA_OPEN))
        assertNull(tv.action)
    }

    @Test fun aVoiceRequest_arrives() {
        val incoming = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(SearchManager.QUERY, "Der kleine Hörtest")
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        val tv = forward(incoming)
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, tv.action)
        assertEquals("Der kleine Hörtest", tv.getStringExtra(SearchManager.QUERY))
        assertEquals("vnd.android.cursor.item/audio", tv.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
    }

    @Test fun theSsoRedirect_keepsItsData() {
        val data = Uri.parse("lauschig://oauth?code=c&state=s")
        val tv = forward(Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(Intent.ACTION_VIEW, tv.action)
        assertEquals(data, tv.data)
        assertTrue(tv.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun aShare_keepsTypeClipAndGrants() {
        val clip = ClipData.newPlainText("link", "https://abs.example.org/item/li_1")
        val incoming = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://abs.example.org/item/li_1")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        incoming.clipData = clip
        val tv = forward(incoming)
        assertEquals("text/plain", tv.type)
        assertEquals("https://abs.example.org/item/li_1", tv.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("https://abs.example.org/item/li_1", tv.clipData?.getItemAt(0)?.text)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, tv.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
