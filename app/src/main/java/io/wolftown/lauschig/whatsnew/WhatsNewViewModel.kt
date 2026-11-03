package io.wolftown.lauschig.whatsnew

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.settings.UiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides once per app start whether the "Was ist neu" sheet shows (see [releasesToShow]). A first install and a
 * version without notes just remember the current version; the sheet's dismissal does that after an update, so a
 * sheet that was never dismissed shows again on the next start.
 */
@HiltViewModel
class WhatsNewViewModel(
    private val settings: UiSettings,
    private val currentVersion: Int,
    private val wasUpdated: () -> Boolean,
    private val releases: List<Release> = WHATS_NEW,
) : ViewModel() {
    @Inject constructor(settings: UiSettings, @ApplicationContext context: Context) :
        this(settings, BuildConfig.VERSION_CODE, { wasUpdated(context) })

    private val _pending = MutableStateFlow<List<Release>>(emptyList())
    val pending: StateFlow<List<Release>> = _pending

    private val _checked = MutableStateFlow(false)
    /** True once the check has run (tests). */
    val checked: StateFlow<Boolean> = _checked

    init {
        viewModelScope.launch {
            try {
                val lastSeen = settings.lastSeenVersion.first()
                val show = releasesToShow(lastSeen, currentVersion, wasUpdated(), releases)
                if (show.isEmpty()) {
                    if (lastSeen == null || lastSeen < currentVersion) settings.setLastSeenVersion(currentVersion)
                } else {
                    _pending.value = show
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("WhatsNew", "check failed", e) // never in the way of the app
            }
            _checked.value = true
        }
    }

    fun dismiss() {
        _pending.value = emptyList()
        viewModelScope.launch { settings.setLastSeenVersion(currentVersion) }
    }
}

/** The package was installed over an older version at least once (a first install has both times equal). */
private fun wasUpdated(context: Context): Boolean = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime > info.firstInstallTime
} catch (e: PackageManager.NameNotFoundException) {
    false
}
