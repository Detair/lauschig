package io.wolftown.lauschig.ui

import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountData
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.account.KeptAccountStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** „Auf dem Gerät liegen noch Downloads von …“ for [account]; [bytes] of its downloads on the device. */
data class PreviousAccountQuestion(val account: KeptAccount, val bytes: Long)

/**
 * After a sign-in: another account kept its downloads on this device (sign-out with „Downloads behalten“). Asked once per
 * kept account: „Löschen“ purges exactly that server id, „Behalten“ never asks again. The same account signing in again
 * just has its data back (its entry goes, no question).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PreviousAccountViewModel(
    accounts: AccountStore,
    private val kept: KeptAccountStore,
    private val bytes: suspend (serverId: String) -> Long,
    private val purge: suspend (serverId: String) -> Unit,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData) :
        this(accounts, kept, data::downloadBytes, data::purge)

    init {
        viewModelScope.launch {
            accounts.account.filterNotNull().map { it.serverId }.distinctUntilChanged().collect { sid ->
                if (kept.accounts.first().any { it.serverId == sid }) kept.remove(sid)
            }
        }
    }

    val question: StateFlow<PreviousAccountQuestion?> = combine(accounts.account, kept.accounts) { a, list ->
        if (a == null) null else list.firstOrNull { it.serverId != a.serverId && !it.asked }
    }
        .distinctUntilChanged()
        .mapLatest { k -> k?.let { PreviousAccountQuestion(it, bytes(it.serverId)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    fun delete() {
        val q = question.value ?: return
        if (_deleting.value) return
        _deleting.value = true
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    purge(q.account.serverId)
                } finally {
                    kept.remove(q.account.serverId)
                    _deleting.value = false
                }
            }
        }
    }

    fun keep() {
        val q = question.value ?: return
        if (_deleting.value) return
        viewModelScope.launch { kept.markAsked(q.account.serverId) }
    }
}

@Composable
fun PreviousAccountDialog(question: PreviousAccountQuestion, deleting: Boolean, onDelete: () -> Unit, onKeep: () -> Unit) {
    val size = Formatter.formatShortFileSize(LocalContext.current, question.bytes)
    AlertDialog(
        // An answer is needed: asked once only.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.previous_account_title)) },
        text = { Text(stringResource(R.string.previous_account_message, question.account.username, question.account.host, size)) },
        confirmButton = {
            TextButton(
                onClick = onDelete, enabled = !deleting, modifier = Modifier.testTag("deletePreviousAccount"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.previous_account_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, enabled = !deleting, modifier = Modifier.testTag("keepPreviousAccount")) {
                Text(stringResource(R.string.previous_account_keep))
            }
        },
        modifier = Modifier.testTag("previousAccountDialog"),
    )
}
