package io.wolftown.lauschig.ui.detail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/** API 33+ only, not yet granted, and never asked before (we ask once, on the first download). */
internal fun shouldAskNotificationPermission(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked

private const val PREFS = "ui_prefs"
private const val KEY_ASKED = "notification_permission_asked"

/**
 * Returns a callback that asks for POST_NOTIFICATIONS once, when the first download is enqueued, so download
 * progress can be shown. Downloads run either way; a denial only hides the notification.
 */
@Composable
internal fun rememberNotificationPermissionOnce(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing depends on it */ }
    return ask@{
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@ask
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, granted, prefs.getBoolean(KEY_ASKED, false))) {
            prefs.edit { putBoolean(KEY_ASKED, true) }
            launcher.launch(permission)
        }
    }
}
