package io.wolftown.lauschig.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountData
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.account.KeptAccountDeletion
import io.wolftown.lauschig.account.KeptAccountStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    /** Purges and forgets the account; false if that failed (the entry then stays, see [KeptAccountDeletion]). */
    private val deleteAccount: suspend (KeptAccount) -> Boolean,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData, deletion: KeptAccountDeletion) :
        this(accounts, kept, data::downloadBytes, deletion::delete)

    init {
        viewModelScope.launch {
            // Every sign-in counts, also the same account again after a sign-out while the app ran.
            accounts.account.map { it?.serverId }.distinctUntilChanged().filterNotNull().collect { sid ->
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

    /** The last „Löschen“ failed; the question stays (with a message) so it can be tried again or kept. */
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    fun delete() {
        val q = question.value ?: return
        if (_deleting.value) return
        _deleting.value = true
        _deleteFailed.value = false
        viewModelScope.launch {
            // The entry is put back as not asked yet on failure: the data stays reachable.
            val ok = deleteAccount(q.account.copy(asked = false))
            _deleteFailed.value = !ok
            _deleting.value = false
        }
    }

    fun keep() {
        val q = question.value ?: return
        if (_deleting.value) return
        _deleteFailed.value = false
        viewModelScope.launch { kept.markAsked(q.account.serverId) }
    }
}

@Composable
fun PreviousAccountDialog(question: PreviousAccountQuestion, deleting: Boolean, onDelete: () -> Unit, onKeep: () -> Unit, failed: Boolean = false) {
    val size = Formatter.formatShortFileSize(LocalContext.current, question.bytes).replace(' ', '\u00A0')
    AlertDialog(
        // An answer is needed: asked once only.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.previous_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.previous_account_message, question.account.username, question.account.host, size))
                if (failed) {
                    Text(
                        stringResource(R.string.kept_delete_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("previousAccountFailed"),
                    )
                }
            }
        },
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
