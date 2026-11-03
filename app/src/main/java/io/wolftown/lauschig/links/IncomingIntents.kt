package io.wolftown.lauschig.links

import android.content.Intent

/** What `MainActivity` does with the links it receives (pure, so the routing is testable). */
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
}
