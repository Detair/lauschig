package io.wolftown.lauschig.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlaybackService
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Homescreen widget: cover, play/pause and ±30 s. Two layouts ([SMALL]: cover with play/pause; [WIDE]: cover, title,
 * chapter or author, −30 s, play/pause, +30 s, progress). Buttons are media-button intents straight to
 * [PlaybackService] (see [WidgetIntents]), so they work while the app process is dead.
 */
class LauschigWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetSource()
        val views = source.views()
        val first = views.first()
        provideContent {
            val view by views.collectAsState(first)
            GlanceTheme { WidgetContent(view) }
        }
    }

    companion object {
        /** 1×1 to 2×2 cells: cover with the play/pause button. */
        val SMALL = DpSize(48.dp, 48.dp)
        /** From about 3×1 cells: the full row. */
        val WIDE = DpSize(200.dp, 48.dp)
    }
}

class LauschigWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LauschigWidget()
}

/** The widget's intents; every media-button intent has its own data URI, so their PendingIntents never merge. */
object WidgetIntents {
    const val TAG_PLAY = "widgetPlayPause"
    const val TAG_BACK = "widgetBack"
    const val TAG_FORWARD = "widgetForward"
    const val TAG_OPEN = "widgetOpen"

    private val mediaButtons = ConcurrentHashMap<Int, Intent>()

    /**
     * One intent per key, built once and never changed (Glance leaves an intent with data alone): the same instance on
     * every render, which also lets tests compare the widget's actions with it.
     */
    fun mediaButton(context: Context, keyCode: Int): Intent = mediaButtons.getOrPut(keyCode) {
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context.packageName, PlaybackService::class.java.name))
            .setData("lauschig://widget/key/$keyCode".toUri())
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    /** The app; with [player] it opens the player when a title is loaded. */
    fun openApp(context: Context, player: Boolean): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (player) putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER) }

    /**
     * Play/pause: PAUSE goes to the running service; PLAY may start it from a dead process, so it is a foreground
     * start (the service resumes the last title and goes foreground); without a title to resume it opens the app.
     */
    fun playPause(context: Context, view: WidgetView): Action = when {
        view.state.playing -> actionStartService(mediaButton(context, KeyEvent.KEYCODE_MEDIA_PAUSE), isForegroundService = false)
        view.canResume -> actionStartService(mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY), isForegroundService = true)
        else -> actionStartActivity(openApp(context, player = false))
    }

    /** ±30 s ([CarSafePlayer][io.wolftown.lauschig.playback.CarSafePlayer] maps rewind/fast-forward to 30 s steps). */
    fun step(context: Context, forward: Boolean): Action = actionStartService(
        mediaButton(context, if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND),
        isForegroundService = false,
    )
}

@Composable
fun WidgetContent(view: WidgetView) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(WidgetIntents.openApp(context, player = view.state.hasTitle))
    val root = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp).background(GlanceTheme.colors.widgetBackground)
    when {
        !view.state.hasTitle -> EmptyWidget(root, open)
        size.width >= LauschigWidget.WIDE.width -> WideWidget(view, root, open)
        else -> SmallWidget(view, root, open)
    }
}

@Composable
private fun EmptyWidget(modifier: GlanceModifier, open: Action) {
    val context = LocalContext.current
    Row(
        modifier.padding(12.dp).clickable(open).semantics { testTag = WidgetIntents.TAG_OPEN },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(R.drawable.ic_widget_cover), contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.size(28.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            context.getString(R.string.widget_empty),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            maxLines = 2,
        )
    }
}

@Composable
private fun SmallWidget(view: WidgetView, modifier: GlanceModifier, open: Action) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Cover(view, GlanceModifier.fillMaxSize().clickable(open))
        PlayPauseButton(view, GlanceModifier.size(44.dp), context)
    }
}

@Composable
private fun WideWidget(view: WidgetView, modifier: GlanceModifier, open: Action) {
    val context = LocalContext.current
    val s = view.state
    val coverEdge = LocalSize.current.height.coerceAtMost(96.dp)
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(view, GlanceModifier.size(coverEdge).cornerRadius(16.dp).clickable(open))
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 10.dp).clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.title, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            s.subtitle?.let {
                Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            if (s.durationMs > 0) {
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator(
                    progress = s.progressPermille / 1000f,
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
            }
        }
        CircleIconButton(
            ImageProvider(R.drawable.ic_widget_back_30), context.getString(R.string.widget_back_30),
            onClick = WidgetIntents.step(context, forward = false),
            modifier = GlanceModifier.size(40.dp).semantics { testTag = WidgetIntents.TAG_BACK },
            backgroundColor = null, contentColor = GlanceTheme.colors.onSurface,
        )
        PlayPauseButton(view, GlanceModifier.size(44.dp), context)
        CircleIconButton(
            ImageProvider(R.drawable.ic_widget_forward_30), context.getString(R.string.widget_forward_30),
            onClick = WidgetIntents.step(context, forward = true),
            modifier = GlanceModifier.size(40.dp).semantics { testTag = WidgetIntents.TAG_FORWARD },
            backgroundColor = null, contentColor = GlanceTheme.colors.onSurface,
        )
    }
}

@Composable
private fun PlayPauseButton(view: WidgetView, modifier: GlanceModifier, context: Context) {
    val playing = view.state.playing
    CircleIconButton(
        ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
        context.getString(if (playing) R.string.widget_pause else R.string.widget_play),
        onClick = WidgetIntents.playPause(context, view),
        modifier = modifier.semantics { testTag = WidgetIntents.TAG_PLAY },
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}

@Composable
private fun Cover(view: WidgetView, modifier: GlanceModifier) {
    val context = LocalContext.current
    val description = context.getString(R.string.widget_open, view.state.title)
    val cover = view.cover
    if (cover != null) {
        Image(ImageProvider(cover), contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(GlanceTheme.colors.secondaryContainer).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.ic_widget_cover), contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer), modifier = GlanceModifier.size(24.dp),
            )
        }
    }
}
