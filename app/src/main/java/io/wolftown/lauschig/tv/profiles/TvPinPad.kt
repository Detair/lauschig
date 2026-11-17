package io.wolftown.lauschig.tv.profiles

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.PIN_LENGTHS
import io.wolftown.lauschig.account.isValidPin
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.profiles.pinCountdown

/** A digit's button is tagged with this plus the digit. */
internal const val TAG_PIN_DIGIT = "tvPinDigit:"
internal const val TAG_PIN_DELETE = "tvPinDelete"
internal const val TAG_PIN_OK = "tvPinOk"
internal const val TAG_PIN_ENTERED = "tvPinEntered"
internal const val TAG_PIN_NOTICE = "tvPinNotice"
internal const val TAG_PIN_WAIT = "tvPinWait"

private val KeySize = 72.dp

/**
 * A PIN entered with the remote: the digits as buttons, the focus starting in the middle (the 5), then delete and OK. The
 * remote's own digit keys work too. No text field, so the TV's keyboard never opens. What is entered is never shown and only
 * held here, in [remember]: OK hands it on and clears it. While [checking], or during a wait ([waitMs]), nothing can be
 * entered. Back calls [onCancel].
 */
@Composable
fun TvPinPad(notice: String?, waitMs: Long, checking: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    val locked = checking || waitMs > 0
    val five = remember { FocusRequester() }
    // Counts the entries handed on: each one empties the pad.
    var handedOn by remember { mutableIntStateOf(0) }
    // To the 5 at the start and whenever the pad starts over: after an entry (the next step, a wrong PIN), and when it opens
    // again after a check or a wait. Otherwise the focus stays on OK, which is off with an empty entry: on a TV nothing would
    // show where the focus is, and OK would do nothing.
    LaunchedEffect(handedOn, locked) { five.requestFocusWhenPlaced() }
    val type = { d: Char -> if (!locked && digits.length < PIN_LENGTHS.last) digits += d }
    val delete = { if (!locked) digits = digits.dropLast(1) }
    val submit = {
        if (!locked && isValidPin(digits)) {
            val entered = digits
            digits = ""
            handedOn++
            onSubmit(entered)
        }
    }

    // Wherever the focus is on the page, and however Back arrives (a key, or the system's back gesture on newer Android): the
    // pad closes, the page under it stays.
    BackHandler { onCancel() }
    Column(
        Modifier.onPreviewKeyEvent { event ->
            val code = event.key.nativeKeyCode
            when {
                code in KEYCODE_0..KEYCODE_9 || code in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> {
                    if (event.type == KeyEventType.KeyDown) type('0' + (if (code >= KEYCODE_NUMPAD_0) code - KEYCODE_NUMPAD_0 else code - KEYCODE_0))
                    true
                }
                event.key == Key.Backspace || event.key == Key.Delete -> {
                    if (event.type == KeyEventType.KeyDown) delete()
                    true
                }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val entered = pluralStringResource(R.plurals.tv_pin_entered, digits.length, digits.length)
        Text(
            // A line of its own height also while empty, so the pad does not jump with the first digit.
            if (digits.isEmpty()) " " else "●".repeat(digits.length),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag(TAG_PIN_ENTERED).clearAndSetSemantics { contentDescription = entered },
        )
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d -> DigitKey(d, enabled = !locked, onClick = { type(d) }, modifier = if (d == '5') Modifier.focusRequester(five) else Modifier) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = delete,
                enabled = !locked,
                modifier = Modifier.size(KeySize).testTag(TAG_PIN_DELETE),
                contentPadding = PaddingValues(0.dp),
            ) {
                KeyFace { Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.tv_pin_delete)) }
            }
            DigitKey('0', enabled = !locked, onClick = { type('0') })
            Button(
                onClick = submit,
                enabled = !locked && isValidPin(digits),
                modifier = Modifier.size(KeySize).testTag(TAG_PIN_OK),
                contentPadding = PaddingValues(0.dp),
            ) {
                KeyFace { Text(stringResource(R.string.pin_ok), style = MaterialTheme.typography.titleMedium) }
            }
        }
        // Always there, so that its new words are read out (AnnouncedLine).
        AnnouncedLine(
            notice,
            tag = TAG_PIN_NOTICE,
            modifier = Modifier.width(KeySize * 3 + 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        if (waitMs > 0) {
            Text(
                stringResource(R.string.pin_wait_left, pinCountdown(waitMs)),
                style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontFeatureSettings = "tnum")),
                modifier = Modifier.testTag(TAG_PIN_WAIT),
            )
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(KeySize).testTag(TAG_PIN_DIGIT + digit),
        contentPadding = PaddingValues(0.dp),
    ) {
        KeyFace { Text(digit.toString(), style = MaterialTheme.typography.headlineSmall) }
    }
}

/** A key's label in the middle of the round key: the button itself puts its content at the top left of its size. */
@Composable
private fun KeyFace(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
