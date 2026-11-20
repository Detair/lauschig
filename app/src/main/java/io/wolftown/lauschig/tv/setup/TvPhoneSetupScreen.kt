package io.wolftown.lauschig.tv.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.serverqr.QrCode
import io.wolftown.lauschig.serverqr.QrCodeImage
import io.wolftown.lauschig.tv.TvDimens

internal const val TAG_PHONE_QR = "tvPhoneQr"
internal const val TAG_PHONE_NUMBER = "tvPhoneNumber"
internal const val TAG_PHONE_TYPE = "tvPhoneType"
internal const val TAG_PHONE_AGAIN = "tvPhoneAgain"
internal const val TAG_PHONE_STATUS = "tvPhoneStatus"
internal const val TAG_PHONE_PROGRESS = "tvPhoneProgress"

/** Big enough to scan from the sofa; [QrCodeImage] keeps it black on white with its quiet zone. */
private val QR_SIZE = 240.dp

private enum class PhoneFocus { TYPE, AGAIN, STATUS }

/**
 * Setting up the TV with the phone: the code to scan and the number to compare, or what stopped it. [onNewCode] is "New code"
 * and "Try again", [onType] the way to the remote's form.
 */
@Composable
fun TvPhoneSetupScreen(state: TvPhoneSetupState, addProfile: Boolean, onNewCode: () -> Unit, onType: () -> Unit) {
    val focus = remember { PhoneFocus.entries.associateWith { FocusRequester() } }
    val target = when (state) {
        TvPhoneSetupState.Starting, is TvPhoneSetupState.Waiting -> PhoneFocus.TYPE
        TvPhoneSetupState.NoHomeNetwork, is TvPhoneSetupState.Stopped -> PhoneFocus.AGAIN
        TvPhoneSetupState.SigningIn -> PhoneFocus.STATUS
    }
    // Each state has its own buttons: the one that leads on takes the focus, so it is never left on a button that went.
    LaunchedEffect(target) { focus.getValue(target).requestFocus() }
    // Leaving now would cut the sign-in off halfway; it ends on its own within seconds.
    BackHandler(enabled = state == TvPhoneSetupState.SigningIn) {}

    Row(
        Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.overscanHorizontal),
    ) {
        Column(Modifier.weight(1f).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (addProfile) R.string.profiles_add else R.string.tv_phone_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            val body = MaterialTheme.typography.bodyLarge
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when (state) {
                TvPhoneSetupState.Starting, is TvPhoneSetupState.Waiting -> {
                    listOf(R.string.tv_phone_step_open, R.string.tv_phone_step_scan, R.string.tv_phone_step_compare).forEachIndexed { i, step ->
                        Text("${i + 1}. " + stringResource(step), style = body)
                    }
                    Text(stringResource(R.string.tv_phone_same_network), style = body, color = muted)
                }
                TvPhoneSetupState.SigningIn -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    // Focusable: with no button on this page, the focus waits here instead of nowhere.
                    Text(
                        stringResource(R.string.tv_phone_signing_in),
                        style = body,
                        modifier = Modifier.focusRequester(focus.getValue(PhoneFocus.STATUS)).focusable().testTag(TAG_PHONE_STATUS),
                    )
                }
                TvPhoneSetupState.NoHomeNetwork -> {
                    Text(stringResource(R.string.tv_phone_no_network), style = body, modifier = Modifier.testTag(TAG_PHONE_STATUS))
                    Text(stringResource(R.string.tv_phone_same_network), style = body, color = muted)
                }
                is TvPhoneSetupState.Stopped ->
                    Text(stringResource(state.reason.message()), style = body, modifier = Modifier.testTag(TAG_PHONE_STATUS))
            }
            if (state != TvPhoneSetupState.SigningIn) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (target == PhoneFocus.AGAIN) {
                        Button(onClick = onNewCode, modifier = Modifier.focusRequester(focus.getValue(PhoneFocus.AGAIN)).testTag(TAG_PHONE_AGAIN)) {
                            Text(stringResource(if (state == TvPhoneSetupState.NoHomeNetwork) R.string.tv_retry else R.string.tv_phone_new_code))
                        }
                        OutlinedButton(onClick = onType, modifier = Modifier.focusRequester(focus.getValue(PhoneFocus.TYPE)).testTag(TAG_PHONE_TYPE)) {
                            Text(stringResource(R.string.tv_phone_type))
                        }
                    } else {
                        Button(onClick = onType, modifier = Modifier.focusRequester(focus.getValue(PhoneFocus.TYPE)).testTag(TAG_PHONE_TYPE)) {
                            Text(stringResource(R.string.tv_phone_type))
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is TvPhoneSetupState.Waiting -> {
                    val modules = remember(state.code) { QrCode.modules(state.code) }
                    QrCodeImage(modules, stringResource(R.string.tv_phone_qr_description), Modifier.size(QR_SIZE).testTag(TAG_PHONE_QR))
                    val digits = state.number
                    // Read digit by digit, as they are compared; "forty-eight twenty-one" would not match the phone's.
                    val spoken = stringResource(R.string.tv_phone_number_description, digits.toList().joinToString(" "))
                    Text(
                        "${digits.take(2)} ${digits.drop(2)}",
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.testTag(TAG_PHONE_NUMBER).semantics { contentDescription = spoken },
                    )
                }
                TvPhoneSetupState.Starting -> Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(48.dp).testTag(TAG_PHONE_PROGRESS))
                }
                else -> Unit
            }
        }
    }
}

private fun TvPhoneSetupStop.message(): Int = when (this) {
    TvPhoneSetupStop.EXPIRED -> R.string.tv_phone_expired
    TvPhoneSetupStop.TOO_MANY_ATTEMPTS -> R.string.tv_phone_too_many
    TvPhoneSetupStop.SERVER_UNREACHABLE -> R.string.tv_phone_unreachable
    TvPhoneSetupStop.SIGN_IN_REFUSED -> R.string.tv_phone_refused
    TvPhoneSetupStop.FAILED -> R.string.tv_phone_failed
}
