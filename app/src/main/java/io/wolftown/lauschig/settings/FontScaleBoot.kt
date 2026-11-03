package io.wolftown.lauschig.settings

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit

/**
 * The app's text size has to be known before the activity has resources (`attachBaseContext`), which is too early for
 * DataStore. This tiny synchronous mirror of [Appearance.fontScalePercent] is read there; `MainActivity` rewrites it
 * and recreates itself whenever the DataStore value differs from the one it was created with.
 *
 * The scale goes into the activity's configuration (not into a Compose `LocalDensity`), so dialogs, sheets and menus –
 * which get their density from their own window – scale too, and Android 14's non-linear font scaling keeps working.
 */
object FontScaleBoot {
    private const val FILE = "font_scale_boot"
    private const val KEY = "percent"

    fun read(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, 100).takeIf { it in Appearance.FONT_SCALES } ?: 100

    /** In-memory at once (the same instance serves [read] in this process), on disk in the background. */
    fun write(context: Context, percent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt(KEY, percent) }
    }

    /** The override for `applyOverrideConfiguration`: the system's font scale times [percent]; null for 100 %. */
    fun overrideFor(system: Configuration, percent: Int): Configuration? =
        if (percent == 100) null else Configuration().apply { fontScale = system.fontScale * percent / 100f }
}
