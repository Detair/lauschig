package io.wolftown.lauschig.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.theme.Spacing

/** The words for a protection. */
internal fun ProfileLockKind.label(): Int = when (this) {
    ProfileLockKind.NONE -> R.string.profiles_protect_off
    ProfileLockKind.DEVICE -> R.string.profiles_protect_device
    ProfileLockKind.PIN -> R.string.profiles_protect_pin
    ProfileLockKind.UNREADABLE -> R.string.profiles_protected
}

/**
 * Everything the gate asks and the choice of a protection, for the sheet and the picker alike. [onSignIn]: the way of a forgotten
 * PIN, the sign-in form for this account.
 */
@Composable
fun ProfileGateDialogs(viewModel: ProfilesViewModel, onSignIn: (serverId: String) -> Unit) {
    val step by viewModel.protection.collectAsStateWithLifecycle()
    when (val s = step) {
        null -> Unit
        is ProtectionStep.Choose -> ProtectionChoiceDialog(s, viewModel::chooseProtection, viewModel::cancelProtection)
        is ProtectionStep.NewPin -> NewPinDialog(s, viewModel::setNewPin, viewModel::cancelProtection)
    }
    val prompt by viewModel.gatePrompt.collectAsStateWithLifecycle()
    val leaveForSignIn = { row: ProfileRow ->
        viewModel.cancelGate()
        onSignIn(row.serverId)
    }
    when (val p = prompt) {
        null -> Unit
        is GatePrompt.Pin -> PinPromptDialog(p, viewModel::submitPin, onForgot = { leaveForSignIn(p.row) }, onCancel = viewModel::cancelGate)
        is GatePrompt.Device -> DeviceLockStep(p, viewModel::deviceLockAnswered)
        is GatePrompt.SignIn -> SignInInsteadDialog(p, onSignIn = { leaveForSignIn(p.row) }, onCancel = viewModel::cancelGate)
    }
}

/**
 * The system's prompt. It is shown at every resume while the question stands (the library shows nothing twice), so also after a
 * recreation or when the app was away in the moment it was due. An answer from before this question does not count.
 */
@Composable
internal fun DeviceLockStep(prompt: GatePrompt.Device, onAnswer: (DeviceLockAnswer) -> Unit) {
    val prompter = LocalDeviceLockPrompter.current
    if (prompter == null) {
        LaunchedEffect(prompt) { onAnswer(DeviceLockAnswer.NOT_AVAILABLE) }
        return
    }
    val title = stringResource(R.string.profiles_device_title, prompt.row.name)
    val subtitle = stringResource(R.string.profiles_device_subtitle)
    val result by prompter.result.collectAsState()
    val answer = result?.takeIf { it.at > prompt.asked }
    LaunchedEffect(answer) { answer?.let { onAnswer(it.answer) } }
    LifecycleResumeEffect(prompt, answer == null) {
        if (answer == null) prompter.show(title, subtitle)
        onPauseOrDispose {}
    }
}

/** „Profil schützen“: Aus, Gerätesperre, App-PIN; and what it is good for. */
@Composable
fun ProtectionChoiceDialog(step: ProtectionStep.Choose, onChoose: (ProfileLockKind) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.profiles_protect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(Modifier.selectableGroup()) {
                    ProtectionOption(ProfileLockKind.NONE, step.current, enabled = true, hint = null, onChoose)
                    ProtectionOption(
                        ProfileLockKind.DEVICE, step.current, enabled = step.deviceLockAvailable,
                        hint = stringResource(if (step.deviceLockAvailable) R.string.profiles_protect_device_hint else R.string.profiles_protect_no_device_lock),
                        onChoose,
                    )
                    ProtectionOption(ProfileLockKind.PIN, step.current, enabled = true, hint = null, onChoose)
                }
                Text(stringResource(R.string.profiles_protect_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.profiles_protect_sso), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnnouncedLine(
                    if (step.saveFailed) stringResource(R.string.profiles_protect_save_failed) else null,
                    tag = "protectionError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_cancel)) } },
        modifier = Modifier.testTag("protectionChoice"),
    )
}

@Composable
private fun ProtectionOption(kind: ProfileLockKind, current: ProfileLockKind, enabled: Boolean, hint: String?, onChoose: (ProfileLockKind) -> Unit) {
    val selected = kind == current
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onChoose(kind) })
            .padding(vertical = Spacing.s)
            .testTag("protection:$kind"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = Spacing.m)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(kind.label()), style = MaterialTheme.typography.bodyLarge, color = color)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** No way in but the sign-in of this account: the lock is gone, it failed, or what protects the profile cannot be read. */
@Composable
fun SignInInsteadDialog(prompt: GatePrompt.SignIn, onSignIn: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.profiles_signin_title)) },
        text = {
            Text(
                stringResource(
                    when (prompt.reason) {
                        SignInReason.DEVICE_LOCK_GONE -> R.string.profiles_signin_device_gone
                        SignInReason.DEVICE_LOCK_FAILED -> R.string.profiles_signin_device_failed
                        SignInReason.UNREADABLE -> R.string.profiles_signin_unreadable
                    },
                    prompt.row.name,
                ),
            )
        },
        confirmButton = { TextButton(onClick = onSignIn, modifier = Modifier.testTag("signInInstead")) { Text(stringResource(R.string.profiles_signin)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_cancel)) } },
        modifier = Modifier.testTag("signInInsteadDialog"),
    )
}
