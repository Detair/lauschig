package io.wolftown.lauschig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.settings.UiSettings
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Start
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Start-router input: [Setup] without an account, [Start] with one; null while the LOCAL account read is
 * pending (a DataStore read, a few ms). Never touches the network, so a dead server or an expired token
 * cannot delay or redirect the start.
 */
@HiltViewModel
class MainViewModel(
    accounts: AccountStore,
    appearanceSource: Flow<Appearance> = flowOf(Appearance()),
    /** "Continue on app start" (see [continueOnLauncherStart]); never blocks the start. */
    private val onStartWithAccount: suspend () -> Unit = {},
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, settings: PlaybackSettings, starter: LastTitleStarter, ui: UiSettings) :
        this(accounts, ui.appearance, { if (settings.continueOnStart.first()) starter.resumeLast(maxAgeMs = CONTINUE_MAX_AGE_MS) })

    /**
     * The look (theme, colors, text size); null until the first local read, so the splash stays up until the first
     * frame can be drawn in the right theme. An unreadable store gives the defaults.
     */
    val appearance: StateFlow<Appearance?> = appearanceSource
        .catch { e -> if (e is CancellationException) throw e else emit(Appearance()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _startDestination = MutableStateFlow<Any?>(null)
    val startDestination: StateFlow<Any?> = _startDestination

    init {
        viewModelScope.launch {
            // An unreadable store must never crash the start: treat it as "no account".
            val account = try {
                accounts.account.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _startDestination.value = if (account == null) Setup else Start
        }
    }

    private var continued = false

    /**
     * The activity was opened from the launcher (not recreated, no deep link or notification): plays the last title
     * if the setting is on. Once per ViewModel, after the local account read.
     */
    fun continueOnLauncherStart() {
        if (continued) return
        continued = true
        viewModelScope.launch {
            if (_startDestination.filterNotNull().first() != Start) return@launch
            try {
                onStartWithAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "continue on start failed", e)
            }
        }
    }
}

/** Continue on start only after a pause shorter than this (an old title the next morning is fine, last week is not). */
const val CONTINUE_MAX_AGE_MS = 12 * 60 * 60_000L

/** A fresh launch from the launcher icon: no recreation, no deep link (SSO), no notification target. */
fun isLauncherStart(intent: android.content.Intent?, recreated: Boolean): Boolean =
    !recreated && intent != null && intent.action == android.content.Intent.ACTION_MAIN &&
        intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER) && intent.data == null &&
        intent.getStringExtra(MainActivity.EXTRA_OPEN) == null
