package io.wolftown.lauschig.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.wolftown.lauschig.diagnostics.Log
import androidx.core.graphics.scale
import androidx.glance.appwidget.updateAll
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.auto.CoverStore
import kotlinx.coroutines.flow.distinctUntilChanged
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.PlaybackService
import io.wolftown.lauschig.playback.loadPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What one widget render needs: the state, the cover (≤ [WidgetSource.COVER_PX]) and whether "play" can resume. */
class WidgetView(val state: WidgetState, val cover: Bitmap?, val canResume: Boolean)

/**
 * Feeds the widget from [WidgetStore]. With nothing stored yet it shows the last title of this device; covers come
 * from [CoverStore] (downloaded, device or cached server cover; a miss starts its background fetch) and are decoded
 * small on the IO dispatcher, never on the main thread and never through the network here.
 */
@Singleton
class WidgetSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WidgetStore,
    private val accounts: AccountStore,
    private val last: LastTitleStarter,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val progress: ProgressDao,
    private val covers: CoverStore,
) {
    /** Bumped when the widget's own cover arrived; the widget then decodes it again. */
    private val coverTick = MutableStateFlow(0)
    private val coverCache = WidgetCoverCache<Bitmap>()

    fun views(): Flow<WidgetView> = combine(store.state, accounts.account.map { it?.serverId }.distinctUntilChanged(), coverTick) { stored, sid, _ -> stored to sid }
        .map { (stored, sid) ->
            val state = WidgetMapping.forDisplay(stored, PlaybackService.running, sid) ?: fallback()
            // "Play" from the widget resumes through the service (onPlaybackResumption); without a title it can resume
            // the button opens the app instead, so no foreground start ends without playback.
            val canResume = state.hasTitle && (state.playing || safe { last.last() } != null)
            val cover = coverCache.get(state.coverRef()) { ref -> safe { covers.file(ref) }?.let(::decode) }
            WidgetView(state, cover, canResume)
        }
        .flowOn(Dispatchers.IO)

    /** Redraws every placed widget (no-op without widgets). */
    suspend fun refresh() {
        try {
            LauschigWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget update failed", e)
        }
    }

    /**
     * A cover arrived in the background (see [CoverStore.onFetched]; the car and SystemUI make it fetch many). Only the
     * widget's own cover redraws it.
     */
    suspend fun coverArrived(serverId: String, libraryItemId: String) {
        if (!coverCache.arrived(serverId, libraryItemId)) return
        coverTick.value++
        refresh()
    }

    private suspend fun fallback(): WidgetState {
        val l = safe { last.last() } ?: return WidgetState()
        val item = safe { loadPlayable(items, episodes, l.serverId, l.itemId) } ?: return WidgetState()
        val p = safe { progress.get(l.serverId, l.itemId) }
        return WidgetState(
            serverId = l.serverId,
            key = l.itemId,
            title = item.item.title,
            subtitle = item.item.author,
            coverVersion = item.item.coverUpdatedAt,
            playing = false,
            positionMs = ((p?.currentTimeSec ?: 0.0) * 1000).toLong(),
            durationMs = ((p?.durationSec?.takeIf { it > 0 } ?: item.item.durationSec) * 1000).toLong(),
        )
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "widget data: $e")
        null
    }

    companion object {
        /** Longest cover edge in the widget (RemoteViews parcel every bitmap to the launcher). */
        const val COVER_PX = 300
        private const val TAG = "LauschigWidget"

        /** Decodes [file] with the longest edge at most [maxPx]; null if it is no image. */
        fun decode(file: File, maxPx: Int = COVER_PX): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val edge = maxOf(bitmap.width, bitmap.height)
            if (edge <= maxPx) return bitmap
            val scale = maxPx.toFloat() / edge
            return bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1))
                .also { if (it !== bitmap) bitmap.recycle() }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetSource(): WidgetSource
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds abstract fun widgetStore(impl: DataStoreWidgetStore): WidgetStore
}
