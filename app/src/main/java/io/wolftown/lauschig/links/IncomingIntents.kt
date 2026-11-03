package io.wolftown.lauschig.links

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.ui.setup.OidcCallback

/** "Spiel X in Lauschig" from the Assistant outside the car. */
data class VoiceRequest(val query: String?, val extras: Bundle?)

/** What `MainActivity` takes from one intent. */
data class Incoming(
    /** A screen to open ([MainActivity.EXTRA_OPEN]: cleanup, player). */
    val open: String? = null,
    val voice: VoiceRequest? = null,
    /** An Audiobookshelf web link (VIEW of a configured host, or shared text). */
    val link: String? = null,
    /** The SSO redirect `lauschig://oauth?…`. */
    val oidc: Uri? = null,
)

/** What `MainActivity` does with the intents it receives (pure, so the routing is testable). */
object IncomingIntents {
    /**
     * The web link an intent carries: a VIEW of an http(s) URL (the generated filter for the configured hosts), or a
     * shared text (`ACTION_SEND`, `text/plain`) with a URL in it. Null for everything else (launcher, SSO callback,
     * voice, notifications).
     */
    fun link(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.toString()
        Intent.ACTION_SEND -> intent.takeIf { it.type?.startsWith("text/") == true }
            ?.let { it.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
            ?.let(AbsLinks::firstUrl)
        else -> null
    }

    /**
     * Takes what [intent] asks for and strips it from the intent. A recreation ([replay]: rotation, process restart)
     * hands the old intent again: voice, links and the SSO redirect were used already and are left alone then. The open
     * extra is removed after its first use.
     */
    fun consume(intent: Intent?, replay: Boolean): Incoming {
        if (intent == null) return Incoming()
        val open = intent.getStringExtra(MainActivity.EXTRA_OPEN)
        intent.removeExtra(MainActivity.EXTRA_OPEN)
        if (replay) return Incoming(open = open)
        var voice: VoiceRequest? = null
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            voice = VoiceRequest(intent.getStringExtra(SearchManager.QUERY), intent.extras)
            intent.action = null
        }
        val link = link(intent)?.also {
            intent.action = null
            intent.data = null
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
        val oidc = intent.data?.takeIf(OidcCallback::isCallback)?.also { intent.data = null }
        return Incoming(open, voice, link, oidc)
    }
}
