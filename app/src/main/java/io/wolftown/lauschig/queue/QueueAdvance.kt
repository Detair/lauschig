package io.wolftown.lauschig.queue

import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which queued title follows when [endedKey] played to its end: the first entry that is not the ended title and can be
 * played right now. Nothing while the sleep timer waits for the end of a chapter (the listener wants to stop).
 * With nothing to play, the regular end of a title applies (finished card with the next-volume suggestion).
 */
object AdvancePolicy {
    fun next(queue: List<String>, endedKey: String, playable: (String) -> Boolean, sleepEndOfChapter: Boolean): String? {
        if (sleepEndOfChapter) return null
        return queue.firstOrNull { it != endedKey && playable(it) }
    }
}

/**
 * Starts the next queued title when one ends ([PlayerController.titleEnded]). The queue wins over the next-volume
 * suggestion: a playable entry starts automatically, the suggestion only remains on the finished title. The ended
 * title (if it was queued too) and the started one leave the queue; unplayable entries (not downloaded while
 * offline) stay for later.
 */
@Singleton
class QueueAdvancer(
    private val serverId: suspend () -> String?,
    private val entries: suspend (serverId: String) -> List<String>,
    private val remove: suspend (serverId: String, key: String) -> Unit,
    private val playable: suspend (serverId: String, key: String) -> Boolean,
    private val sleepEndOfChapter: () -> Boolean,
    private val play: (serverId: String, key: String) -> Unit,
) {
    private var player: PlayerController? = null

    @Inject constructor(
        player: PlayerController,
        accounts: AccountStore,
        queue: QueueDao,
        downloads: DownloadDao,
        api: ApiProvider,
        sleepTimer: SleepTimer,
    ) : this(
        serverId = { accounts.account.first()?.serverId },
        entries = { sid -> queue.entries(sid).map { it.itemKey } },
        remove = queue::remove,
        playable = { sid, key -> api.connectionState.value is ConnectionState.Online || downloads.get(sid, key)?.state == DownloadState.COMPLETE },
        sleepEndOfChapter = { sleepTimer.state.value?.mode == SleepMode.EndOfChapter },
        play = player::play,
    ) {
        this.player = player
    }

    fun start(scope: CoroutineScope) {
        val p = player ?: return
        scope.launch { p.titleEnded.collect { onEnded(it) } }
    }

    suspend fun onEnded(endedKey: String) {
        val sid = serverId() ?: return
        val queue = entries(sid)
        if (endedKey in queue) remove(sid, endedKey)
        val ok = HashMap<String, Boolean>()
        for (k in queue) if (k != endedKey) ok[k] = playable(sid, k)
        val next = AdvancePolicy.next(queue, endedKey, { ok[it] == true }, sleepEndOfChapter()) ?: return
        remove(sid, next)
        play(sid, next)
    }
}
