package io.wolftown.lauschig.tv.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.setup.SetupUiState
import io.wolftown.lauschig.ui.setup.SetupViewModel
import io.wolftown.lauschig.ui.setup.message

internal const val TAG_LOCAL = "tvSetupLocal"
internal const val TAG_PUBLIC = "tvSetupPublic"
internal const val TAG_USERNAME = "tvSetupUsername"
internal const val TAG_PASSWORD = "tvSetupPassword"
internal const val TAG_LOGIN = "tvSetupLogin"
internal const val TAG_ERROR = "tvSetupError"
internal const val TAG_SHOW_PASSWORD = "tvSetupShowPassword"
internal const val TAG_CLEARTEXT_CONFIRM = "tvCleartextConfirm"
internal const val TAG_CLEARTEXT_CANCEL = "tvCleartextCancel"

/**
 * The sign-in on a TV: the phone's form without what a TV cannot do (SSO needs a browser, the scan a camera, the Wi-Fi
 * binding a location permission). [onDone] after a successful sign-in.
 */
@Composable
fun TvSetupRoute(onDone: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    TvSetupScreen(
        state = state,
        onLocal = viewModel::onLocalChanged,
        onPublic = viewModel::onPublicChanged,
        onUsername = viewModel::onUsernameChanged,
        onPassword = viewModel::onPasswordChanged,
        onLogin = viewModel::login,
        onConfirmCleartext = viewModel::confirmCleartext,
        onDismissCleartext = viewModel::dismissCleartext,
    )
}

private enum class SetupFocus { LOCAL, PUBLIC, USERNAME, PASSWORD, LOGIN }

/** The first field still to fill in; with all of them filled, the button. */
private fun firstEmpty(state: SetupUiState): SetupFocus = when {
    !state.addressesFixed && state.local.isEmpty() && state.public.isEmpty() -> SetupFocus.LOCAL
    state.username.isEmpty() -> SetupFocus.USERNAME
    state.password.isEmpty() -> SetupFocus.PASSWORD
    else -> SetupFocus.LOGIN
}

@Composable
fun TvSetupScreen(
    state: SetupUiState,
    onLocal: (String) -> Unit,
    onPublic: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onConfirmCleartext: () -> Unit,
    onDismissCleartext: () -> Unit,
) {
    val focus = remember { SetupFocus.entries.associateWith { FocusRequester() } }
    // The view model fills in a known account's addresses and name a moment after the form shows. Until the person presses
    // a key, the focus follows to the first field still empty; after that it stays where they put it.
    var settled by rememberSaveable { mutableStateOf(false) }
    val target = firstEmpty(state)
    LaunchedEffect(target, settled) { if (!settled) focus.getValue(target).requestFocus() }
    val typed: ((String) -> Unit) -> (String) -> Unit = { change -> { settled = true; change(it) } }
    // The field being typed in, if any; the others only take the focus as a whole.
    var editing by remember { mutableStateOf<SetupFocus?>(null) }
    var passwordShown by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxSize()
            .padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical)
            .onPreviewKeyEvent { settled = true; false },
        horizontalArrangement = Arrangement.spacedBy(TvDimens.overscanHorizontal),
    ) {
        Column(Modifier.weight(0.4f).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (title, intro) = when {
                state.unlockServerId != null ->
                    stringResource(R.string.setup_unlock_title) to stringResource(R.string.setup_unlock_intro, state.unlockName ?: state.username)
                state.addProfile -> stringResource(R.string.profiles_add) to when (state.nobodyActive) {
                    true -> stringResource(R.string.setup_add_intro_nobody)
                    false -> stringResource(R.string.setup_add_intro)
                    null -> null
                }
                else -> stringResource(R.string.setup_title) to stringResource(R.string.setup_intro)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            intro?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Scrolls only if the error line pushes the button off the screen; the focused field brings itself into view.
        Column(Modifier.weight(0.6f).verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val fields = listOfNotNull(
                SetupFocus.LOCAL.takeIf { !state.addressesFixed }, SetupFocus.PUBLIC.takeIf { !state.addressesFixed },
                SetupFocus.USERNAME, SetupFocus.PASSWORD,
            )
            // The keyboard's "next" goes straight on to typing in the next field; after the password to the button.
            fun next(field: SetupFocus) {
                val following = fields.getOrNull(fields.indexOf(field) + 1)
                if (following != null) editing = following
                else {
                    editing = null
                    focus.getValue(SetupFocus.LOGIN).requestFocus()
                }
            }
            @Composable
            fun Field(field: SetupFocus, value: String, onChange: (String) -> Unit, label: Int, tag: String, placeholder: Int? = null, type: KeyboardType = KeyboardType.Text) =
                TvTextField(
                    value = value, onValueChange = typed(onChange), label = stringResource(label), tag = tag,
                    focusRequester = focus.getValue(field), editing = editing == field,
                    onEditingChange = { on -> editing = if (on) field else editing?.takeIf { it != field } },
                    onNext = { next(field) }, last = field == fields.last(),
                    placeholder = placeholder?.let { stringResource(it) }, keyboardType = type, shown = passwordShown,
                )
            if (!state.addressesFixed) {
                Field(SetupFocus.LOCAL, state.local, onLocal, R.string.setup_local_address, TAG_LOCAL, R.string.setup_local_placeholder, KeyboardType.Uri)
                Field(SetupFocus.PUBLIC, state.public, onPublic, R.string.setup_public_address, TAG_PUBLIC, R.string.setup_public_placeholder, KeyboardType.Uri)
            }
            Field(SetupFocus.USERNAME, state.username, onUsername, R.string.setup_username, TAG_USERNAME)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(SetupFocus.PASSWORD, state.password, onPassword, R.string.setup_password, TAG_PASSWORD, type = KeyboardType.Password)
                }
                // A step to the right of the field: typing with a remote goes wrong easily, so the password can be checked.
                IconButton(onClick = { passwordShown = !passwordShown }, modifier = Modifier.testTag(TAG_SHOW_PASSWORD)) {
                    Icon(
                        if (passwordShown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (passwordShown) R.string.password_hide else R.string.password_show),
                    )
                }
            }
            AnnouncedLine(
                state.error?.let { stringResource(it.message()) },
                tag = TAG_ERROR,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            // Focusable also while it is off: the remote's way down ends here, and the person sees what is still missing.
            Button(
                onClick = onLogin,
                enabled = state.canLogin,
                modifier = Modifier.focusRequester(focus.getValue(SetupFocus.LOGIN)).testTag(TAG_LOGIN),
                // The default barely marks a focused button that is off; on a TV the focus must always be seen.
                border = ButtonDefaults.border(
                    focusedDisabledBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant), shape = CircleShape),
                ),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.setup_login))
            }
        }
    }

    state.cleartextPrompt?.let { hosts -> CleartextDialog(hosts, onConfirmCleartext, onDismissCleartext) }
}

/**
 * A text field for the remote. As a whole it takes the focus like a button; only OK puts the focus into the text, and the TV's
 * keyboard opens. (The TV keyboard opens whenever a text input gets the focus, so moving through the form would otherwise
 * cover it at every step.) Up and down always leave it for the field above or below, also while typing, as the text would
 * otherwise keep them to move its cursor. Back closes the keyboard; the focus stays in the text until up or down.
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    focusRequester: FocusRequester,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    last: Boolean,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** A password field shows its text (the button beside it). */
    shown: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val text = remember { FocusRequester() }
    // One source for the frame and the text: the field looks focused while either of them has the focus.
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(editing) { if (editing) text.requestFocus() }
    val password = keyboardType == KeyboardType.Password
    Box(
        Modifier.fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.hasFocus && editing) onEditingChange(false) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // Only once the focus has gone: the text must not lose the ability to hold it while it still has it.
                    Key.DirectionDown -> { if (focusManager.moveFocus(FocusDirection.Down)) onEditingChange(false); true }
                    Key.DirectionUp -> { if (focusManager.moveFocus(FocusDirection.Up)) onEditingChange(false); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (editing) keyboard?.show() else onEditingChange(true)
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .testTag(tag),
    ) {
        OutlinedTextField(
            value, onValueChange,
            Modifier.fillMaxWidth().focusRequester(text).focusProperties { canFocus = editing },
            singleLine = true,
            label = { androidx.compose.material3.Text(label) },
            placeholder = placeholder?.let { { androidx.compose.material3.Text(it) } },
            visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (last) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            interactionSource = interaction,
        )
    }
}

/** The phone's question about an unencrypted server, with the focus on the safe answer. */
@Composable
private fun CleartextDialog(hosts: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.setup_cleartext_title)) },
        text = { androidx.compose.material3.Text(stringResource(R.string.setup_cleartext_message, hosts.joinToString("\n"))) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag(TAG_CLEARTEXT_CONFIRM)) { Text(stringResource(R.string.setup_cleartext_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus).testTag(TAG_CLEARTEXT_CANCEL)) {
                Text(stringResource(R.string.setup_cancel))
            }
        },
    )
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}
