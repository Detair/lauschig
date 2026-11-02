package io.wolftown.lauschig.download

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.playback.loadPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a title in the background when playing it started a stream ("download on play"). On Wi-Fi it is queued
 * right away under the usual rules; on mobile data the "over mobile data" setting decides: ask ([DownloadPrompt.Metered]),
 * always, or wait for Wi-Fi. A title that already has a download (complete, queued, running, or paused by the user) is
 * left alone. One that failed is started again unless the storage was full; one paused for the stream
 * ([DownloadWorker.STREAM_PRIORITY]) only on an unmetered network, so the same weak mobile line does not ask again.
 * Also answers the prompts ([answerMetered], [answerContention]).
 */
@Singleton
class PlayDownloadStarter(
    private val enabled: Flow<Boolean>,
    private val metered: Flow<MeteredDownload>,
    private val setMetered: suspend (MeteredDownload) -> Unit,
    private val isMetered: () -> Boolean,
    private val row: suspend (serverId: String, itemId: String) -> DownloadEntity?,
    /** Title and total size (sum of the audio files) of a playable. */
    private val describe: suspend (serverId: String, itemId: String) -> Pair<String, Long>?,
    private val downloads: DownloadManager,
    private val prompts: DownloadPrompts,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        settings: DownloadSettings,
        downloadDao: DownloadDao,
        itemDao: ItemDao,
        episodeDao: EpisodeDao,
        downloads: DownloadManager,
        prompts: DownloadPrompts,
    ) : this(
        enabled = settings.downloadOnPlay,
        metered = settings.meteredOnPlay,
        setMetered = settings::setMeteredOnPlay,
        isMetered = { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true },
        row = downloadDao::get,
        describe = { serverId, itemId ->
            loadPlayable(itemDao, episodeDao, serverId, itemId)?.let { it.item.title to it.audioFiles.sumOf { f -> f.sizeBytes } }
        },
        downloads = downloads,
        prompts = prompts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** Fire-and-forget entry for the player (main thread). */
    fun start(serverId: String, itemId: String) {
        scope.launch {
            try {
                onStreamStarted(serverId, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download on play failed", e)
            }
        }
    }

    suspend fun onStreamStarted(serverId: String, itemId: String) {
        if (!enabled.first()) return
        val existing = row(serverId, itemId)
        val restart = when {
            existing == null -> true
            existing.state == DownloadState.FAILED -> existing.error != STORAGE_FULL
            existing.state == DownloadState.PAUSED && existing.error == DownloadWorker.STREAM_PRIORITY -> !isMetered()
            else -> false
        }
        if (!restart) return
        if (!isMetered()) {
            downloads.enqueue(serverId, itemId)
            return
        }
        when (metered.first()) {
            MeteredDownload.ALWAYS -> downloads.enqueue(serverId, itemId, NetworkType.CONNECTED)
            MeteredDownload.WIFI -> downloads.enqueue(serverId, itemId, NetworkType.UNMETERED)
            MeteredDownload.ASK -> {
                val (title, bytes) = describe(serverId, itemId) ?: return
                prompts.show(DownloadPrompt.Metered(serverId, itemId, title, bytes))
            }
        }
    }

    /** [now]: over mobile data right away, else wait for Wi-Fi. [remember] stores the answer as the setting. */
    suspend fun answerMetered(prompt: DownloadPrompt.Metered, now: Boolean, remember: Boolean) {
        prompts.clear(prompt)
        if (remember) setMetered(if (now) MeteredDownload.ALWAYS else MeteredDownload.WIFI)
        downloads.enqueue(prompt.serverId, prompt.itemId, if (now) NetworkType.CONNECTED else NetworkType.UNMETERED)
    }

    /**
     * [download]: resume the paused download (the caller pauses playback, so it runs at full speed); else it stays
     * paused and the stream keeps the connection.
     */
    suspend fun answerContention(prompt: DownloadPrompt.Contention, download: Boolean) {
        prompts.clear(prompt)
        if (download) downloads.enqueue(prompt.serverId, prompt.itemId, prompt.network)
    }

    /** Closed without an answer: nothing is downloaded (a paused download stays paused). */
    fun dismiss(prompt: DownloadPrompt) = prompts.clear(prompt)

    private companion object {
        const val TAG = "PlayDownloadStarter"
        const val STORAGE_FULL = "storage_full"
    }
}
