package io.wolftown.lauschig.tv.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A text field for the remote. As a whole it takes the focus like a button; only OK puts the focus into the text, and the TV's
 * keyboard opens. (The TV keyboard opens whenever a text input gets the focus, so moving through a page would otherwise
 * cover it at every step.) Up and down always leave it for what is above or below, also while typing, as the text would
 * otherwise keep them to move its cursor. Back closes the keyboard; the focus stays in the text until up or down.
 * The keyboard's action key ([imeAction]) calls [onImeAction].
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    focusRequester: FocusRequester,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** A password field shows its text (a button beside it). */
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
            // The wrapper is what TalkBack lands on; the text inside keeps its own node (not merged) for typing.
            .semantics { contentDescription = if (value.isEmpty() || password) label else "$label: $value" }
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }, onSearch = { onImeAction() }, onGo = { onImeAction() }),
            interactionSource = interaction,
        )
    }
}
