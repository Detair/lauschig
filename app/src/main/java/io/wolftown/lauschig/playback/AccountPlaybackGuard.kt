package io.wolftown.lauschig.playback

import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.CancellationException

/**
 * Keeps the service's player to the signed-in account (main thread). The sign-out flow stops the player and then changes the
 * account; a title that starts in between (a headset key, the car, the widget, the queue's next title) would otherwise
 * play on for the next account, and its stream would go out with the next account's token.
 */
internal class AccountPlaybackGuard(
    /** The server id and key of the title in the player. */
    private val title: () -> Pair<String, String>?,
    /** Stops the player and empties it. */
    private val stop: () -> Unit,
    /** Another account is active: the title kept for an empty player (the widget's) was the last one's. */
    private val forgetShownTitle: () -> Unit,
    private val signedIn: suspend () -> String?,
) {
    private var known = false
    private var account: String? = null
    private var lastSignedIn: String? = null

    /**
     * The signed-in account is [next], at every answer of the store. A title goes if an account was left and the title is
     * not the new account's; that includes a device title, the next account starts quietly. The first answer at the start of
     * the service leaves nothing (a title may play already), neither does a sign-in. The title kept for the widget goes when
     * another person is signed in than the last one, also with a sign-out in between.
     */
    fun accountChanged(next: String?) {
        val left = known && account != null && account != next
        val anotherPerson = next != null && lastSignedIn != null && next != lastSignedIn
        known = true
        account = next
        if (next != null) lastSignedIn = next
        if (left) title()?.let {
            if (it.first != next) {
                Log.i(TAG, "a title of the account that was left is still in the player: stopped")
                stop()
            }
        }
        if (anotherPerson) forgetShownTitle()
    }

    /**
     * Another title is in the player, by whatever path (the queue's next one, a car start resolved just before the switch). A
     * server title of an account that is not the signed-in one goes. A store that cannot be read stops nothing.
     */
    suspend fun titleChanged() {
        val now = title() ?: return
        if (DeviceLibrary.isLocal(now.first)) return
        val owner = try {
            signedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "account unreadable", e)
            return
        }
        if (owner != now.first && title() == now) {
            Log.i(TAG, "a title of another account came into the player: stopped")
            stop()
        }
    }

    private companion object {
        const val TAG = "AccountPlaybackGuard"
    }
}
