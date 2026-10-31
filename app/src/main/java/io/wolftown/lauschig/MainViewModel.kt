package io.wolftown.lauschig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Start
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
class MainViewModel @Inject constructor(accounts: AccountStore) : ViewModel() {
    private val _startDestination = MutableStateFlow<Any?>(null)
    val startDestination: StateFlow<Any?> = _startDestination

    init {
        viewModelScope.launch { _startDestination.value = if (accounts.account.first() == null) Setup else Start }
    }
}
