package io.wolftown.lauschig.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.wolftown.lauschig.R

/**
 * A password (or token) field with an eye to show what has been typed: a long password on a slow
 * keyboard easily gets a character too many. Hidden at first. Whether it is shown is held with
 * [remember] on purpose, not saveable: back on the page, after turning the device or after the
 * process comes back it is hidden again.
 *
 * Known gap: Compose Foundation 1.12.1 keeps the accessibility Password flag once the field exists
 * (CoreTextFieldSemanticsModifierNode never stores a changed isPassword), so TalkBack still treats
 * a shown password as hidden. What is drawn is right.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes showDescription: Int = R.string.password_show,
    @StringRes hideDescription: Int = R.string.password_hide,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValueChange, modifier, singleLine = true,
        label = label,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        // Password in both states: the keyboard neither suggests nor learns it.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (shown) hideDescription else showDescription),
                )
            }
        },
    )
}
