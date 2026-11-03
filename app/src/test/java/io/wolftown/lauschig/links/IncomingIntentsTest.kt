package io.wolftown.lauschig.links

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import io.wolftown.lauschig.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IncomingIntentsTest {
    @Test fun view_ofAWebLink() {
        val url = "https://audiobookshelf.wolftown.io/item/abc"
        assertEquals(url, IncomingIntents.link(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
    }

    @Test fun send_textWithALink() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Hör mal https://abs.example/item/abc")
        assertEquals("https://abs.example/item/abc", IncomingIntents.link(share))
        assertEquals(null, IncomingIntents.link(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "nur Text")))
        assertEquals(null, IncomingIntents.link(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "https://abs.example/item/abc")))
    }

    @Test fun everythingElse_isNoLink() {
        assertEquals(null, IncomingIntents.link(null))
        assertEquals(null, IncomingIntents.link(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertEquals(null, IncomingIntents.link(Intent(Intent.ACTION_VIEW, Uri.parse("lauschig://oauth?code=1&state=2"))))
        assertEquals(null, IncomingIntents.link(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)))
        assertEquals(null, IncomingIntents.link(Intent().putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER)))
    }
}
