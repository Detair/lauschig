package io.wolftown.lauschig.playback

import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.sameOrigin
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * The calls of the player's streams. A stream goes through the client of the account its title belongs to: the URL's fragment
 * names it ([MediaItemFactory]). That client sends nothing while another account is signed in ([io.wolftown.lauschig.net.AuthInterceptor]),
 * so a title that lands around a profile switch never streams with the next account's token.
 *
 * Requests to the last online server ([lastOnline]) keep their authenticated client even after the state flipped to offline: a
 * failed probe on a busy link must not turn the next stream request into a 401 that stops playback. Other origins, and a
 * URL that names no owner, get the plain client without credentials.
 */
internal class StreamCalls(private val lastOnline: () -> HttpUrl?, private val api: ApiProvider) : Call.Factory {
    override fun newCall(request: Request): Call {
        val owner = request.url.fragment
        val online = lastOnline()?.takeIf { request.url.sameOrigin(it) }
        val client = if (owner != null && online != null) api.clientFor(owner, online) else api.unauthenticatedClient()
        return client.newCall(request)
    }
}
