package io.wolftown.lauschig.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import io.wolftown.lauschig.MainActivity

/**
 * A television: the UI mode says so, or the device runs the TV launcher. The second covers boxes and projectors that
 * report a normal UI mode but have no touchscreen and only a remote.
 */
fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/**
 * Where "open the player" leads (the media session, the now-playing card): on a television the TV interface, as the phone
 * interface cannot be used with a remote; elsewhere the phone interface.
 */
fun playerScreenIntent(context: Context): Intent {
    val target = if (context.isTelevision()) TvActivity::class.java else MainActivity::class.java
    return Intent(context, target).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER)
}
