package io.wolftown.lauschig.tv.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.ProfileRow
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.profiles.SignInReason
import io.wolftown.lauschig.ui.profiles.pinNoticeText

/** A profile's tile is tagged with this plus its server id. */
internal const val TAG_PROFILE = "tvProfile:"
internal const val TAG_PROFILE_ADD = "tvProfileAdd"
internal const val TAG_PIN_TITLE = "tvPinTitle"
internal const val TAG_PIN_FORGOT = "tvPinForgot"
internal const val TAG_SIGN_IN_DIALOG = "tvSignInDialog"
internal const val TAG_SIGN_IN = "tvSignIn"
internal const val TAG_SIGN_IN_CANCEL = "tvSignInCancel"

/** The add tile's place in the remembered focus; a server id is hex. */
private const val ADD = "add"

private val TileShape = RoundedCornerShape(16.dp)

/**
 * "Who is listening?" (TV spec E8). The active profile goes in after its protection; another one through the switch, which
 * passes the same gate. [onChosen] once the active one is in; a switch brings the other profile's pages by itself.
 */
@Composable
fun TvProfilesRoute(onChosen: () -> Unit, onAdd: () -> Unit, onSignIn: (String) -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val rows by viewModel.profiles.collectAsStateWithLifecycle()
    val gate by viewModel.gatePrompt.collectAsStateWithLifecycle()
    val entered by viewModel.enteredActive.collectAsStateWithLifecycle()
    LaunchedEffect(entered) { if (entered) onChosen() }
    val list = rows ?: return
    TvProfilesScreen(
        rows = list,
        gate = gate,
        onChoose = { row -> if (row.active) viewModel.enterActive() else viewModel.switchTo(row) },
        onPin = viewModel::submitPin,
        onCancelGate = viewModel::cancelGate,
        onAdd = onAdd,
        onSignIn = { serverId ->
            viewModel.cancelGate()
            onSignIn(serverId)
        },
    )
}

/**
 * The profiles as tiles, the focus on the active one, "Add profile" at the end. A PIN is asked on a page of its own; the way
 * of a forgotten PIN, and of a profile behind the device lock (which a TV does not offer, TV spec E12), is the sign-in of
 * that account.
 */
@Composable
fun TvProfilesScreen(
    rows: List<ProfileRow>,
    gate: GatePrompt?,
    onChoose: (ProfileRow) -> Unit,
    onPin: (String) -> Unit,
    onCancelGate: () -> Unit,
    onAdd: () -> Unit,
    onSignIn: (String) -> Unit,
) {
    // The tile last in focus: back from the PIN, the focus is where it was.
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical)) {
        if (gate is GatePrompt.Pin) {
            PinPage(gate, onPin, onCancelGate, onForgot = { onSignIn(gate.row.serverId) })
        } else {
            val target = focused?.takeIf { f -> f == ADD || rows.any { it.serverId == f } }
                ?: rows.firstOrNull { it.active }?.serverId ?: rows.firstOrNull()?.serverId ?: ADD
            ProfileChoice(rows, target, onFocused = { focused = it }, onChoose, onAdd)
        }
    }
    when (gate) {
        is GatePrompt.SignIn -> SignInDialog(
            stringResource(
                when (gate.reason) {
                    SignInReason.DEVICE_LOCK_GONE -> R.string.profiles_signin_device_gone
                    SignInReason.DEVICE_LOCK_FAILED -> R.string.profiles_signin_device_failed
                    SignInReason.UNREADABLE -> R.string.profiles_signin_unreadable
                },
                gate.row.name,
            ),
            onSignIn = { onSignIn(gate.row.serverId) },
            onCancel = onCancelGate,
        )
        // The system's prompt is not shown on a TV: the way in is the sign-in, as when the lock is gone.
        is GatePrompt.Device -> SignInDialog(
            stringResource(R.string.tv_profiles_device_lock, gate.row.name),
            onSignIn = { onSignIn(gate.row.serverId) },
            onCancel = onCancelGate,
        )
        else -> Unit
    }
}

@Composable
private fun ProfileChoice(rows: List<ProfileRow>, target: String, onFocused: (String) -> Unit, onChoose: (ProfileRow) -> Unit, onAdd: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWhenPlaced() }
    fun Modifier.tile(id: String) = then(if (id == target) Modifier.focusRequester(focus) else Modifier)
        .onFocusChanged { if (it.isFocused) onFocused(id) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically)) {
        Text(
            stringResource(R.string.profiles_picker_title),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.gap * 2, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 16.dp),
        ) {
            items(rows, key = { it.serverId }) { row ->
                ProfileTile(row, onClick = { onChoose(row) }, modifier = Modifier.tile(row.serverId).testTag(TAG_PROFILE + row.serverId))
            }
            item(key = ADD) {
                Tile(onClick = onAdd, modifier = Modifier.tile(ADD).testTag(TAG_PROFILE_ADD), label = stringResource(R.string.profiles_add), detail = null) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(row: ProfileRow, onClick: () -> Unit, modifier: Modifier) {
    val status = listOfNotNull(
        stringResource(R.string.profiles_active).takeIf { row.active },
        stringResource(R.string.profiles_needs_reauth).takeIf { row.needsReauth },
        stringResource(R.string.profiles_protected).takeIf { row.protection != ProfileLockKind.NONE },
    )
    Tile(onClick, modifier, row.name, detail = (listOfNotNull(row.host) + status).joinToString(" · ").ifEmpty { null }) {
        Text(row.name.take(1).uppercase(), style = MaterialTheme.typography.displaySmall)
        if (row.protection != ProfileLockKind.NONE) {
            Icon(
                Icons.Rounded.Lock, contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(4.dp),
            )
        }
    }
}

/** A large tile: a round sign, the name and a line about it. */
@Composable
private fun Tile(onClick: () -> Unit, modifier: Modifier, label: String, detail: String?, sign: @Composable BoxScope.() -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.width(180.dp),
        shape = CardDefaults.shape(TileShape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = TileShape)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center, content = sign)
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Always a line, so that all tiles are the same height.
            Text(
                detail ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PinPage(prompt: GatePrompt.Pin, onPin: (String) -> Unit, onCancel: () -> Unit, onForgot: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TvDimens.overscanHorizontal), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                stringResource(R.string.pin_title, prompt.row.name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag(TAG_PIN_TITLE).semantics { heading() },
            )
            // A step to the left of the pad.
            OutlinedButton(onClick = onForgot, modifier = Modifier.testTag(TAG_PIN_FORGOT)) { Text(stringResource(R.string.pin_forgot)) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TvPinPad(pinNoticeText(prompt.notice), prompt.waitMs, prompt.checking, onSubmit = onPin, onCancel = onCancel)
        }
    }
}

/** No way in but this account's sign-in. The focus on "Sign in": it is the only way on. */
@Composable
private fun SignInDialog(text: String, onSignIn: () -> Unit, onCancel: () -> Unit) {
    val signInFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(stringResource(R.string.profiles_signin_title)) },
        text = { androidx.compose.material3.Text(text) },
        confirmButton = {
            // Here, in the dialog's own composition: only once the button exists can the focus be asked for.
            LaunchedEffect(Unit) { signInFocus.requestFocus() }
            Button(onClick = onSignIn, modifier = Modifier.focusRequester(signInFocus).testTag(TAG_SIGN_IN)) { Text(stringResource(R.string.profiles_signin)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(TAG_SIGN_IN_CANCEL)) { Text(stringResource(R.string.setup_cancel)) }
        },
        modifier = Modifier.testTag(TAG_SIGN_IN_DIALOG),
    )
}
