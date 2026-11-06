package io.wolftown.lauschig.ui.profiles

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.PIN_LENGTHS
import io.wolftown.lauschig.account.isValidPin
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.theme.Spacing

/** The dialog around a PIN field. */
internal fun interface PinDialogFrame {
    @Composable
    fun Show(onDismiss: () -> Unit, tag: String, title: @Composable () -> Unit, text: @Composable () -> Unit, confirm: @Composable () -> Unit, dismiss: @Composable () -> Unit)
}

/**
 * Looks like the app's other dialogs, but all of it scrolls: with the keyboard up on a phone held sideways there is room for a
 * few lines, and an `AlertDialog` would give them to its title and buttons, not to the field. A test puts the contents in the
 * page: under Robolectric a text field in a dialog window is measured over and over until the heap is gone, in any `AlertDialog` too.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal val LocalPinDialogFrame = staticCompositionLocalOf<PinDialogFrame> {
    PinDialogFrame { onDismiss, tag, title, text, confirm, dismiss ->
        BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag(tag)) {
            Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                    text()
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        dismiss()
                        confirm()
                    }
                }
            }
        }
    }
}

/**
 * A dialog window only slides up for the keyboard: on a short screen its buttons stay below it. This way the window gets smaller
 * and its text scrolls.
 */
@Composable
private fun DialogFitsAboveTheKeyboard() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
}

/** Digits and nothing else, and no more than a PIN can have. */
internal fun pinDigits(entered: String): String = entered.filter { it in '0'..'9' }.take(PIN_LENGTHS.last)

/**
 * The field for a PIN. Never shown, and what it holds is the caller's: in [remember], so not saved anywhere, and a rotation
 * clears it.
 */
@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    DialogFitsAboveTheKeyboard()
    // Ready to type as soon as this dialog's window has the focus, also after a rotation; the sheet below may have it before.
    val focus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(enabled, windowFocused) { if (enabled && windowFocused) focus.requestFocus() }
    OutlinedTextField(
        value,
        { onValueChange(pinDigits(it)) },
        Modifier.fillMaxWidth().focusRequester(focus).testTag("pinField"),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.pin_field)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
private fun pinNoticeText(notice: PinNotice?): String? = when (notice) {
    null -> null
    is PinNotice.Wrong -> pluralStringResource(R.plurals.pin_wrong, notice.triesBeforeWait, notice.triesBeforeWait)
    is PinNotice.Wait -> stringResource(R.string.pin_wait, pinWaitText(notice.ms))
    PinNotice.Ready -> stringResource(R.string.pin_ready)
    PinNotice.CheckFailed -> stringResource(R.string.pin_check_failed)
}

/** „30 Sekunden“, „2 Minuten“: rounded up, a wait is never said to be shorter than it is. */
@Composable
private fun pinWaitText(ms: Long): String {
    val seconds = ((ms + 999) / 1_000).toInt()
    return if (seconds < 60) pluralStringResource(R.plurals.pin_wait_seconds, seconds, seconds)
    else ((seconds + 59) / 60).let { pluralStringResource(R.plurals.pin_wait_minutes, it, it) }
}

/** What is left of a wait, for the eye: `0:27`. */
internal fun pinCountdown(ms: Long): String {
    val seconds = (ms + 999) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * „PIN für …“. The notice is one line that is always there, so its new words are read out ([AnnouncedLine]); the countdown is
 * for the eye and not a live region, it would talk every second. „PIN vergessen?“ is there during a wait too.
 */
@Composable
fun PinPromptDialog(prompt: GatePrompt.Pin, onSubmit: (String) -> Unit, onForgot: () -> Unit, onCancel: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    val waiting = prompt.waitMs > 0
    val submit = {
        if (isValidPin(digits) && !prompt.checking && !waiting) {
            val entered = digits
            digits = ""
            onSubmit(entered)
        }
    }
    LocalPinDialogFrame.current.Show(
        onCancel,
        "pinPrompt",
        title = { Text(stringResource(R.string.pin_title, prompt.row.name)) },
        text = { PinPromptBody(prompt, digits, { digits = it }, submit, onForgot) },
        confirm = {
            TextButton(onClick = submit, enabled = isValidPin(digits) && !prompt.checking && !waiting, modifier = Modifier.testTag("pinOk")) {
                if (prompt.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.pin_ok))
            }
        },
        dismiss = { TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_cancel)) } },
    )
}

@Composable
internal fun PinPromptBody(prompt: GatePrompt.Pin, digits: String, onDigits: (String) -> Unit, onDone: () -> Unit, onForgot: () -> Unit) {
    val waiting = prompt.waitMs > 0
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PinField(digits, onDigits, enabled = !waiting, onDone)
        AnnouncedLine(
            pinNoticeText(prompt.notice),
            tag = "pinNotice",
            style = MaterialTheme.typography.bodyMedium,
            color = if (prompt.notice == PinNotice.Ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (waiting) {
            Text(
                stringResource(R.string.pin_wait_left, pinCountdown(prompt.waitMs)),
                style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                modifier = Modifier.testTag("pinCountdown"),
            )
        }
        TextButton(onClick = onForgot, modifier = Modifier.testTag("pinForgot")) { Text(stringResource(R.string.pin_forgot)) }
    }
}

/**
 * A new app PIN, twice. Both entries stay in this dialog and are gone with it, or with a rotation: then it starts over. The line
 * under the field says which entry is due, and is read out when that changes.
 */
@Composable
fun NewPinDialog(step: ProtectionStep.NewPin, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var digits by remember { mutableStateOf("") }
    var differed by remember { mutableStateOf(false) }
    // While it is saved it is decided: a dialog that closes as cancelled would say otherwise.
    val cancel = { if (!step.saving) onCancel() }
    val submit = {
        val entered = digits
        val earlier = first
        when {
            step.saving || !isValidPin(entered) -> Unit
            earlier == null -> {
                first = entered
                digits = ""
                differed = false
            }
            earlier == entered -> onDone(entered)
            else -> {
                first = null
                digits = ""
                differed = true
            }
        }
    }
    LocalPinDialogFrame.current.Show(
        cancel,
        "newPin",
        title = { Text(stringResource(R.string.pin_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.pin_new_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PinField(digits, { digits = it }, enabled = true, onDone = submit)
                AnnouncedLine(
                    when {
                        differed -> stringResource(R.string.pin_new_differs)
                        first != null -> stringResource(R.string.pin_new_again)
                        else -> null
                    },
                    tag = "pinNotice",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (differed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirm = {
            TextButton(onClick = submit, enabled = isValidPin(digits) && !step.saving, modifier = Modifier.testTag("pinOk")) {
                if (step.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (first == null) R.string.pin_next else R.string.pin_ok))
            }
        },
        dismiss = { TextButton(onClick = cancel, enabled = !step.saving) { Text(stringResource(R.string.setup_cancel)) } },
    )
}
