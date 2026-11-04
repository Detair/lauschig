package io.wolftown.lauschig.ui.theme

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** A cover's swatch key: the model (local file path, or the URL with `?ts=`) plus the cover version, so a re-downloaded file under the same path gets a new key. */
fun swatchKey(model: Any, coverVersion: Long): String = "$model@$coverVersion"

/**
 * One colour per cover key. A decoded cover is kept until the entry is evicted (LRU, [capacity] entries); a cover
 * that could not be decoded (offline, 404, broken file) is kept as "no swatch" ([Color.Unspecified]) for
 * [failureTtlMs], so a failing cover is not requested again on every composition but is tried again later.
 */
class SwatchCache(
    private val capacity: Int = 256,
    private val failureTtlMs: Long = FAILURE_TTL_MS,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Entry(val color: Color, val until: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    /** The swatch for [key], [Color.Unspecified] for "none / failed recently", null when unknown. */
    @Synchronized
    fun get(key: String): Color? {
        val e = entries[key] ?: return null
        if (now() > e.until) {
            entries.remove(key)
            return null
        }
        return e.color
    }

    @Synchronized
    fun put(key: String, color: Color) {
        entries[key] = Entry(color, Long.MAX_VALUE)
    }

    @Synchronized
    fun putFailure(key: String) {
        entries[key] = Entry(Color.Unspecified, now() + failureTtlMs)
    }

    companion object {
        const val FAILURE_TTL_MS = 60_000L
    }
}

/** Decodes a cover and picks its swatch: a colour, [Color.Unspecified] when it has none, null when it cannot be decoded. */
fun interface CoverSwatchLoader {
    suspend fun load(context: Context, model: Any): Color?
}

/** The loader [rememberCoverSwatch] uses; tests provide a fake. */
val LocalCoverSwatchLoader = staticCompositionLocalOf<CoverSwatchLoader> { CoilSwatchLoader }

/** The app-wide swatch memory. */
internal val coverSwatchCache = SwatchCache()

/**
 * Loads a small software bitmap through the app's Coil image loader and picks the swatch ([swatchOf]).
 *
 * The cover shown next to the tint (`CoverArt`) is usually requested at the same moment. To use the image that request
 * puts into Coil's disk cache instead of downloading the cover a second time, a network cover is first read from the
 * caches only, then – if it is not there yet – once more after [SECOND_LOOK_MS], and only then fetched by this request
 * itself. (The memory cache cannot be shared: it is keyed by the decoded size.) A local file is read directly.
 */
object CoilSwatchLoader : CoverSwatchLoader {
    /** Small: Palette needs only a thumbnail. */
    private const val SAMPLE_PX = 112
    private const val SECOND_LOOK_MS = 1_500L

    override suspend fun load(context: Context, model: Any): Color? {
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context).data(model).size(SAMPLE_PX).allowHardware(false).build()
        val result = if (model is File) loader.execute(request) else {
            val fromCaches = request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            loader.execute(fromCaches) as? SuccessResult
                ?: run { delay(SECOND_LOOK_MS); loader.execute(fromCaches) as? SuccessResult }
                ?: loader.execute(request)
        }
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) { swatchOf(Palette.from(bitmap).maximumColorCount(16).generate()) }
    }
}

/** A swatch Palette found: its colour and how many of the sampled pixels it stands for. */
data class SwatchOption(val rgb: Int, val population: Int)

/** Below this share of the sampled pixels a swatch is a detail of the cover, not its character. */
const val MIN_SWATCH_SHARE = 0.05f

/**
 * The tint's colour: muted, dark muted, vibrant, dominant – the first in that order that covers at least
 * [MIN_SWATCH_SHARE] of the image ([total] pixels), else the dominant one; null without any swatch. So a vivid cover with
 * a small grey area tints in its own colour, and a calm cover still gets its muted tone.
 */
fun pickSwatch(muted: SwatchOption?, darkMuted: SwatchOption?, vibrant: SwatchOption?, dominant: SwatchOption?, total: Int): Int? {
    val enough = (total * MIN_SWATCH_SHARE).coerceAtLeast(1f)
    return listOfNotNull(muted, darkMuted, vibrant, dominant).firstOrNull { it.population >= enough }?.rgb ?: dominant?.rgb
}

/** [pickSwatch] over [palette]'s swatches; [Color.Unspecified] when it found none. */
fun swatchOf(palette: Palette): Color {
    fun Palette.Swatch?.option() = this?.let { SwatchOption(it.rgb, it.population) }
    val total = palette.swatches.sumOf { it.population }
    val rgb = pickSwatch(palette.mutedSwatch.option(), palette.darkMutedSwatch.option(), palette.vibrantSwatch.option(), palette.dominantSwatch.option(), total)
    return rgb?.let(::Color) ?: Color.Unspecified
}

/** The swatch for [key] from [cache], else loads it and remembers the outcome (a failure for a while). */
internal suspend fun resolveSwatch(context: Context, model: Any, key: String, loader: CoverSwatchLoader, cache: SwatchCache): Color {
    cache.get(key)?.let { return it }
    val color = try {
        loader.load(context, model)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    if (color == null) cache.putFailure(key) else cache.put(key, color)
    return color ?: Color.Unspecified
}

/**
 * The colour of [model]'s cover (a `coverModel`, [coverVersion] its server version), or null without a model and when
 * the cover has no usable colour. A remembered swatch is there in the first frame. While a new cover of this slot is
 * still loading (another title in the player), the previous swatch stays, so the tint goes from one cover's colour
 * straight to the next instead of dipping through the plain surface.
 */
@Composable
fun rememberCoverSwatch(model: Any?, coverVersion: Long = 0): Color? {
    val key = model?.let { swatchKey(it, coverVersion) }
    val context = LocalContext.current
    val loader = LocalCoverSwatchLoader.current
    val cached = remember(key) { key?.let(coverSwatchCache::get) }
    // The last swatch this slot resolved; shown while the next one loads.
    var last by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(key) {
        last = when {
            model == null || key == null -> null
            cached != null -> cached
            else -> resolveSwatch(context, model, key, loader, coverSwatchCache)
        }
    }
    val shown = when {
        key == null -> null
        cached != null -> cached
        else -> last
    }
    return shown?.takeIf { it.isSpecified }
}
