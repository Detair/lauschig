package io.wolftown.lauschig.auto

import io.wolftown.lauschig.diagnostics.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.TimelineMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The chapters of the title in the service's player, for everything that does not go through the app: the chapter
 * buttons ([seekChapter]) and the chapter title as `subtitle` of the playing item, which Android Auto and the system
 * media controls show under the title (AA has no chapter-aware seek bar). The subtitle is written with
 * `replaceMediaItem` on an item with the same URI, which ExoPlayer applies in place (no re-prepare, no gap).
 * Main thread only.
 */
class ChapterTracker(
    private val player: Player,
    private val loadItem: suspend (serverId: String, key: String) -> ItemWithDetails?,
    private val scope: CoroutineScope,
    /** The loaded title changed; [hasChapters]: it has at least two chapters (the chapter buttons make sense). */
    private val onTitle: (hasChapters: Boolean) -> Unit,
    private val tickMs: Long = 2_000,
) : Player.Listener {
    private var key: Pair<String, String>? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var mapper: TimelineMapper? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    fun start() {
        player.addListener(this)
        titleChanged()
    }

    fun release() {
        player.removeListener(this)
        loadJob?.cancel()
        tickJob?.cancel()
    }

    val hasChapters: Boolean get() = chapters.size > 1

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        titleChanged()
        publish()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tickJob?.cancel()
        if (isPlaying) tickJob = scope.launch { while (true) { publish(); delay(tickMs) } }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = publish()

    /** Jumps to the start of this chapter (or the previous one right after a start), or to the next one. */
    fun seekChapter(back: Boolean): Boolean {
        val m = mapper ?: return false
        val pos = positionSec() ?: return false
        val starts = chapters.map { it.startSec }
        val target = (if (back) CarControls.chapterBack(starts, pos) else CarControls.chapterNext(starts, pos)) ?: return false
        val (index, offsetMs) = m.toFile(target)
        player.seekTo(index, offsetMs)
        return true
    }

    private fun positionSec(): Double? = mapper?.toGlobal(player.currentMediaItemIndex, player.currentPosition)

    private fun currentKey(): Pair<String, String>? {
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return null
        return (extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null) to (extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
    }

    private fun titleChanged() {
        val k = currentKey()
        if (k == key) return
        key = k
        chapters = emptyList()
        mapper = null
        loadJob?.cancel()
        onTitle(false)
        if (k == null) return
        loadJob = scope.launch {
            try {
                val item = loadItem(k.first, k.second) ?: return@launch
                if (key != k) return@launch
                chapters = item.chapters.sortedBy { it.startSec }
                mapper = TimelineMapper(item.audioFiles)
                onTitle(hasChapters)
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loading chapters failed", e)
            }
        }
    }

    /** Writes the current chapter's title into the playing item, if it is not there yet. */
    private fun publish() {
        if (!hasChapters || currentKey() != key) return
        val m = mapper ?: return
        val pos = positionSec() ?: return
        val title = chapters.getOrNull(m.chapterAt(pos, chapters))?.title?.takeIf { it.isNotBlank() } ?: return
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.subtitle?.toString() == title) return
        val updated = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setSubtitle(title).build()).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    private companion object {
        const val TAG = "ChapterTracker"
    }
}
