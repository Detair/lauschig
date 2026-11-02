package io.wolftown.lauschig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Start
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** "Continue on app start": runs once per activity after the local account read, never blocking the start. */
    private val onStartWithAccount: suspend () -> Unit = {},
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, settings: PlaybackSettings, starter: LastTitleStarter) :
        this(accounts, { if (settings.continueOnStart.first()) starter.resumeLast() })

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
            if (account != null) {
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
}
