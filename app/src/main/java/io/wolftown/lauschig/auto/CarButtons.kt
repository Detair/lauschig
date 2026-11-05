package io.wolftown.lauschig.auto

import android.content.res.Resources
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.settings.PlaybackSettings
import java.text.NumberFormat
import kotlin.math.abs

/**
 * The media buttons of the session (Android Auto, notification, system media controls): the seek steps of the settings
 * in the back/forward slots instead of previous/next file, then speed, sleep timer and chapter jumps. Labels and icons
 * follow the state.
 */
@OptIn(UnstableApi::class)
class CarButtons(private val res: Resources) {
    /** What the buttons show; a new value means new buttons. */
    data class State(
        val speed: Float,
        val sleep: SleepMode?,
        val hasChapters: Boolean,
        val seekBackSec: Int = PlaybackSettings.DEFAULT_SEEK_BACK,
        val seekForwardSec: Int = PlaybackSettings.DEFAULT_SEEK_FORWARD,
    )

    fun buttons(state: State): List<CommandButton> = buildList {
        add(seekButton(state.seekBackSec, forward = false))
        add(seekButton(state.seekForwardSec, forward = true))
        add(
            CommandButton.Builder(speedIcon(state.speed))
                .setDisplayName(res.getString(R.string.car_speed, speedText(state.speed)))
                .setSessionCommand(command(SPEED))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
        add(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(if (state.sleep == null) R.drawable.ic_car_sleep_off else R.drawable.ic_car_sleep_on)
                .setDisplayName(sleepText(state.sleep))
                .setSessionCommand(command(SLEEP))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
        if (state.hasChapters) {
            add(button(CommandButton.ICON_PREVIOUS, R.string.car_chapter_back, CHAPTER_BACK, CommandButton.SLOT_OVERFLOW))
            add(button(CommandButton.ICON_NEXT, R.string.car_chapter_next, CHAPTER_NEXT, CommandButton.SLOT_OVERFLOW))
        }
    }

    private fun seekButton(sec: Int, forward: Boolean) = CommandButton.Builder(seekIcon(sec, forward))
        .setDisplayName(res.getQuantityString(if (forward) R.plurals.car_seek_forward else R.plurals.car_seek_back, sec, sec))
        .setSessionCommand(command(if (forward) SEEK_FORWARD else SEEK_BACK))
        .setSlots(if (forward) CommandButton.SLOT_FORWARD else CommandButton.SLOT_BACK)
        .build()

    private fun button(icon: Int, label: Int, action: String, slot: Int) = CommandButton.Builder(icon)
        .setDisplayName(res.getString(label))
        .setSessionCommand(command(action))
        .setSlots(slot)
        .build()

    private fun sleepText(mode: SleepMode?): String = when (mode) {
        null -> res.getString(R.string.car_sleep_off)
        is SleepMode.Minutes -> res.getString(R.string.car_sleep_minutes, mode.minutes)
        SleepMode.EndOfChapter -> res.getString(R.string.car_sleep_chapter)
    }

    private fun speedText(speed: Float): String = NumberFormat.getNumberInstance(res.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
    }.format(speed.toDouble())

    companion object {
        const val SEEK_BACK = "io.wolftown.lauschig.SEEK_BACK"
        const val SEEK_FORWARD = "io.wolftown.lauschig.SEEK_FORWARD"
        const val SPEED = "io.wolftown.lauschig.SPEED"
        const val SLEEP = "io.wolftown.lauschig.SLEEP"
        const val CHAPTER_BACK = "io.wolftown.lauschig.CHAPTER_BACK"
        const val CHAPTER_NEXT = "io.wolftown.lauschig.CHAPTER_NEXT"
        val ACTIONS = listOf(SEEK_BACK, SEEK_FORWARD, SPEED, SLEEP, CHAPTER_BACK, CHAPTER_NEXT)

        fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

        /** The built-in icon with the number of seconds where there is one (5, 10, 15, 30), else the plain arrow. */
        fun seekIcon(sec: Int, forward: Boolean): Int = (if (forward) FORWARD_ICONS else BACK_ICONS)[sec]
            ?: if (forward) CommandButton.ICON_SKIP_FORWARD else CommandButton.ICON_SKIP_BACK

        private val BACK_ICONS = mapOf(
            5 to CommandButton.ICON_SKIP_BACK_5, 10 to CommandButton.ICON_SKIP_BACK_10,
            15 to CommandButton.ICON_SKIP_BACK_15, 30 to CommandButton.ICON_SKIP_BACK_30,
        )
        private val FORWARD_ICONS = mapOf(
            5 to CommandButton.ICON_SKIP_FORWARD_5, 10 to CommandButton.ICON_SKIP_FORWARD_10,
            15 to CommandButton.ICON_SKIP_FORWARD_15, 30 to CommandButton.ICON_SKIP_FORWARD_30,
        )

        /** The built-in speed icons where one matches the speed, else the generic one. */
        fun speedIcon(speed: Float): Int = SPEED_ICONS.entries.firstOrNull { abs(it.key - speed) < 0.01f }?.value ?: CommandButton.ICON_PLAYBACK_SPEED

        private val SPEED_ICONS = mapOf(
            0.5f to CommandButton.ICON_PLAYBACK_SPEED_0_5,
            0.8f to CommandButton.ICON_PLAYBACK_SPEED_0_8,
            1.0f to CommandButton.ICON_PLAYBACK_SPEED_1_0,
            1.2f to CommandButton.ICON_PLAYBACK_SPEED_1_2,
            1.5f to CommandButton.ICON_PLAYBACK_SPEED_1_5,
            1.8f to CommandButton.ICON_PLAYBACK_SPEED_1_8,
            2.0f to CommandButton.ICON_PLAYBACK_SPEED_2_0,
        )
    }
}
