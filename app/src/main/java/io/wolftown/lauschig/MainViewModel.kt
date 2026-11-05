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
import io.wolftown.lauschig.ui.nav.TabLayout
import io.wolftown.lauschig.ui.nav.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Start-router input: [Setup] without an account, the chosen start tab ([TabLayout.start], default [Start]) with one;
 * null while the LOCAL account and layout reads are pending (DataStore reads, a few ms). Never touches the network, so a dead server or an expired token
 * cannot delay or redirect the start.
 */
@HiltViewModel
class MainViewModel(
    accounts: AccountStore,
    appearanceSource: Flow<Appearance> = flowOf(Appearance()),
    tabLayoutSource: Flow<TabLayout> = flowOf(TabLayout.DEFAULT),
    /** "Continue on app start" (see [continueOnLauncherStart]); never blocks the start. */
    private val onStartWithAccount: suspend () -> Unit = {},
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, settings: PlaybackSettings, starter: LastTitleStarter, ui: UiSettings) :
        this(accounts, ui.appearance, ui.tabLayout, { if (settings.continueOnStart.first()) starter.resumeLast(maxAgeMs = CONTINUE_MAX_AGE_MS) })

    private val layouts = tabLayoutSource.catch { e -> if (e is CancellationException) throw e else emit(TabLayout.DEFAULT) }

    /** The tabs of the bottom bar / rail; follows the settings at once (the start screen only on the next cold start). */
    val tabLayout: StateFlow<TabLayout> = layouts.stateIn(viewModelScope, SharingStarted.Eagerly, TabLayout.DEFAULT)

    /**
     * The look (theme, colors, text size); null until the first local read, so the splash stays up until the first
     * frame can be drawn in the right theme. An unreadable store gives the defaults.
     */
    val appearance: StateFlow<Appearance?> = appearanceSource
        .catch { e -> if (e is CancellationException) throw e else emit(Appearance()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * An account is signed in (null until the first read; an unreadable store counts as signed out). Turns false when
     * the user signs out while the app runs: the UI then shows the sign-in with a fresh back stack.
     */
    val signedIn: StateFlow<Boolean?> = accounts.account
        .map { it != null }
        .catch { e -> if (e is CancellationException) throw e else emit(false) }
        .distinctUntilChanged()
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
            // The chosen start screen, read once (a later change applies on the next cold start). Intents (widget, links,
            // notifications) navigate on top of it, so they keep their target.
            _startDestination.value = if (account == null) Setup else layouts.first().start.route()
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
            // Signed in = anything but Setup (the start screen may be any tab).
            if (_startDestination.filterNotNull().first() == Setup) return@launch
            try {
                onStartWithAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.wolftown.lauschig.diagnostics.Log.w("MainViewModel", "continue on start failed", e)
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
