package io.wolftown.lauschig.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One colour per cover, remembered in memory by the cover model (local file path, or the server URL that carries the
 * cover version), so reopening a title tints at once. Only successful decodes are remembered – a cover that could not
 * load (offline, server hiccup) is tried again next time.
 */
internal object CoverSwatches {
    /** Small: Palette needs only a thumbnail, and Coil decodes it from its disk cache. */
    private const val SAMPLE_PX = 112

    private val cache = LruCache<String, Color>(256) // Color.Unspecified = decoded, no usable swatch

    fun cached(key: String): Color? = cache.get(key)

    suspend fun load(context: Context, model: Any, key: String): Color? = try {
        val request = ImageRequest.Builder(context).data(model).size(SAMPLE_PX).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        result?.let {
            val color = withContext(Dispatchers.Default) { swatchOf(Palette.from(it.image.toBitmap()).maximumColorCount(16).generate()) }
            cache.put(key, color)
            color
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Muted first (calm in light and dark), then dark-muted, then whatever dominates. */
    private fun swatchOf(palette: Palette): Color =
        (palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color) ?: Color.Unspecified
}

/**
 * The colour of [model]'s cover (a [coverModel][io.wolftown.lauschig.ui.common.coverModel]), or null while it loads,
 * without a model and when the cover has no usable colour. A remembered swatch is there in the first frame.
 */
@Composable
fun rememberCoverSwatch(model: Any?): Color? {
    val key = model?.toString()
    val context = LocalContext.current
    var swatch by remember(key) { mutableStateOf(key?.let(CoverSwatches::cached)) }
    if (model != null && key != null && swatch == null) {
        LaunchedEffect(key) { CoverSwatches.load(context, model, key)?.let { swatch = it } }
    }
    return swatch?.takeIf { it.isSpecified }
}
