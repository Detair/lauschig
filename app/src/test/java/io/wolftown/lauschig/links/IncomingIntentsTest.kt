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

    // --- MainActivity's routing (review 4B #9): what it takes from an intent, and what it strips so a recreation
    // (which hands the old intent again) does not repeat it ---

    @Test fun consume_link_stripsIt_soItIsUsedOnce() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://abs.example/item/abc"))
        assertEquals("https://abs.example/item/abc", IncomingIntents.consume(intent, replay = false).link)
        assertEquals(null, intent.data)
        assertEquals(null, intent.action)
        assertEquals(Incoming(), IncomingIntents.consume(intent, replay = false))
    }

    @Test fun consume_sharedText_removesTheText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://abs.example/item/abc")
        assertEquals("https://abs.example/item/abc", IncomingIntents.consume(intent, replay = false).link)
        assertEquals(null, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun consume_onReplay_neitherOpensNorStrips() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://abs.example/item/abc"))
        assertEquals(null, IncomingIntents.consume(intent, replay = true).link)
        assertEquals("https://abs.example/item/abc", intent.dataString)
        val sso = Intent(Intent.ACTION_VIEW, Uri.parse("lauschig://oauth?code=1&state=2"))
        assertEquals(null, IncomingIntents.consume(sso, replay = true).oidc)
        val voice = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, "Testreihe")
        assertEquals(null, IncomingIntents.consume(voice, replay = true).voice)
    }

    @Test fun consume_onReplay_theOpenExtraIsDropped() {
        // After a process death Recents restores the task with its original intent: the widget's or notification's
        // "open the player" was used then and must not pull the user out of where they were.
        for (target in listOf(MainActivity.OPEN_PLAYER, MainActivity.OPEN_CLEANUP)) {
            val restored = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_OPEN, target)
            assertEquals(Incoming(), IncomingIntents.consume(restored, replay = true))
            assertEquals(null, restored.getStringExtra(MainActivity.EXTRA_OPEN))
        }
    }

    @Test fun consume_ssoCallback_isNeverALink() {
        val sso = Intent(Intent.ACTION_VIEW, Uri.parse("lauschig://oauth?code=1&state=2"))
        val r = IncomingIntents.consume(sso, replay = false)
        assertEquals(null, r.link)
        assertEquals("lauschig://oauth?code=1&state=2", r.oidc.toString())
        assertEquals(null, sso.data)
    }

    @Test fun consume_voice_andTheOpenExtra() {
        val voice = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, "Testreihe")
        assertEquals("Testreihe", IncomingIntents.consume(voice, replay = false).voice!!.query)
        assertEquals(null, voice.action)
        val open = Intent().putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER)
        assertEquals(MainActivity.OPEN_PLAYER, IncomingIntents.consume(open, replay = false).open)
        assertEquals(null, open.getStringExtra(MainActivity.EXTRA_OPEN))
    }
}
