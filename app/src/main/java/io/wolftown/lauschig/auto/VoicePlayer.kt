package io.wolftown.lauschig.auto

import android.os.Bundle
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Spiel X in Lauschig" outside the car: the Assistant opens the app with `MEDIA_PLAY_FROM_SEARCH`. Same resolution as
 * the car's play-from-search ([CarSearch.resolve]), played through the app's [PlayerController].
 */
@Singleton
class VoicePlayer @Inject constructor(
    private val search: CarSearch,
    private val accounts: AccountStore,
    private val api: ApiProvider,
    private val lastTitle: LastTitleStarter,
    private val player: PlayerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun playFromSearch(query: String?, extras: Bundle?) {
        scope.launch {
            // Like the player: wait for the first connection check, so a streamed title is not taken for offline.
            val online = awaitConnectionAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS)
            val ctx = CarContext(accounts.account.first()?.serverId, online)
            val title = search.resolve(query.orEmpty(), CarStarts.hints(extras), ctx) {
                lastTitle.last()?.let { CarNode.Title(it.serverId, it.itemId) }
            }
            if (title == null) {
                Log.i(TAG, "voice: nothing found")
                return@launch
            }
            player.play(title.serverId, title.key)
        }
    }

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
