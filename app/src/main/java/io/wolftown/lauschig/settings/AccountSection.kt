package io.wolftown.lauschig.settings

import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountSignOut
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.SignOutResult
import io.wolftown.lauschig.account.displayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The signed-in account as the "Konto" section shows it. */
@Immutable
data class AccountInfo(val username: String, val host: String, val isAdmin: Boolean)

/** Where the sign-out dialog stands; null = closed. */
sealed interface SignOutStep {
    /** Pending changes are being pushed („Fortschritt wird übertragen …“). */
    data object Checking : SignOutStep

    /** The question; [keepDownloads] is the checkbox (default on: no data loss by default). */
    data class Confirm(val unsynced: Int, val downloadBytes: Long, val keepDownloads: Boolean = true) : SignOutStep {
        val hasDownloads: Boolean get() = downloadBytes > 0

        /**
         * Something to keep: downloads, or changes not on the server yet (then the checkbox keeps the account's data).
         * Neither: no checkbox, everything of the account goes.
         */
        val canKeep: Boolean get() = hasDownloads || unsynced > 0
        val keeps: Boolean get() = canKeep && keepDownloads
    }

    /** Signing out; cannot be cancelled. When it is done the account is gone and the app shows the sign-in. */
    data object SigningOut : SignOutStep
}

@HiltViewModel
class AccountSectionViewModel @Inject constructor(accounts: AccountStore, private val signOut: AccountSignOut) : ViewModel() {
    val account: StateFlow<AccountInfo?> = accounts.account
        .map { a -> a?.let { AccountInfo(it.username, it.displayHost, it.isAdmin) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _step = MutableStateFlow<SignOutStep?>(null)
    val step: StateFlow<SignOutStep?> = _step.asStateFlow()
    private var checkJob: Job? = null

    /** "Abmelden": pauses playback and pushes what is pending, then asks. */
    fun startSignOut() {
        if (_step.value != null) return
        _step.value = SignOutStep.Checking
        checkJob = viewModelScope.launch {
            val check = try {
                signOut.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sign-out check failed", e)
                null
            }
            // No account any more (or the check failed): nothing to ask.
            _step.value = check?.let { SignOutStep.Confirm(it.unsynced, it.downloadBytes) }
        }
    }

    fun setKeepDownloads(keep: Boolean) = _step.update { (it as? SignOutStep.Confirm)?.copy(keepDownloads = keep) ?: it }

    fun cancel() {
        if (_step.value == SignOutStep.SigningOut) return
        checkJob?.cancel()
        _step.value = null
    }

    fun confirm() {
        val question = _step.value as? SignOutStep.Confirm ?: return
        _step.value = SignOutStep.SigningOut
        viewModelScope.launch {
            val result = try {
                signOut.execute(keepDownloads = question.keeps, shownUnsynced = question.unsynced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sign-out failed", e)
                null
            }
            // More changes than shown (playback was resumed meanwhile): ask again with the new count and the same choice.
            _step.value = (result as? SignOutResult.Changed)?.check?.let { question.copy(unsynced = it.unsynced, downloadBytes = it.downloadBytes) }
        }
    }

    private companion object {
        const val TAG = "AccountSection"
    }
}

/** Settings → "Konto" (first section): who is signed in where, and "Abmelden". */
@Composable
fun AccountSection(viewModel: AccountSectionViewModel = hiltViewModel()) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    AccountSectionContent(account, step, viewModel::startSignOut, viewModel::setKeepDownloads, viewModel::confirm, viewModel::cancel)
}

@Composable
fun AccountSectionContent(
    account: AccountInfo?,
    step: SignOutStep?,
    onSignOut: () -> Unit,
    onKeepDownloads: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val a = account ?: return
    Section(R.string.settings_section_account)
    ListItem(
        headlineContent = { Text(a.username) },
        supportingContent = { Text(if (a.isAdmin) "${a.host} · ${stringResource(R.string.account_admin)}" else a.host) },
        leadingContent = { Icon(Icons.Rounded.AccountCircle, contentDescription = null) },
        modifier = Modifier.testTag("accountRow"),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.sign_out)) },
        supportingContent = { Text(stringResource(R.string.sign_out_hint)) },
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = step == null, onClick = onSignOut).testTag("signOut"),
    )
    step?.let { SignOutDialog(it, a.host, onKeepDownloads, onConfirm, onCancel) }
}

@Composable
fun SignOutDialog(step: SignOutStep, host: String, onKeepDownloads: (Boolean) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val running = step == SignOutStep.SigningOut
    AlertDialog(
        onDismissRequest = { if (!running) onCancel() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
        title = { Text(stringResource(if (running) R.string.sign_out_running else R.string.sign_out_title)) },
        text = {
            when (step) {
                SignOutStep.Checking -> Busy(stringResource(R.string.sign_out_syncing))
                SignOutStep.SigningOut -> Busy(null)
                is SignOutStep.Confirm -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sign_out_message, host))
                    if (step.canKeep) {
                        // "22 MB" never breaks between number and unit. Keeping keeps the downloads and the changes.
                        val label = if (step.hasDownloads) {
                            stringResource(R.string.sign_out_keep_downloads, Formatter.formatShortFileSize(LocalContext.current, step.downloadBytes).replace(' ', '\u00A0'))
                        } else {
                            stringResource(R.string.sign_out_keep_data)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(step.keepDownloads, role = Role.Checkbox, onValueChange = onKeepDownloads)
                                .testTag("keepDownloads"),
                        ) {
                            Checkbox(checked = step.keepDownloads, onCheckedChange = null)
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (step.unsynced > 0) {
                        Text(
                            pluralStringResource(
                                if (step.keeps) R.plurals.sign_out_unsynced_kept else R.plurals.sign_out_unsynced_lost, step.unsynced, step.unsynced,
                            ),
                            color = if (step.keeps) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("unsyncedWarning"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = step is SignOutStep.Confirm, modifier = Modifier.testTag("confirmSignOut")) {
                Text(stringResource(R.string.sign_out_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !running) { Text(stringResource(R.string.sign_out_cancel)) }
        },
        modifier = Modifier.testTag("signOutDialog"),
    )
}

@Composable
private fun Busy(text: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        if (text != null) Text(text, modifier = Modifier.padding(start = 16.dp))
    }
}
