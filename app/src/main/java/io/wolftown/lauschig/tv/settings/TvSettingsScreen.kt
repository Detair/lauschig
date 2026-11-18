package io.wolftown.lauschig.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ReconnectState
import io.wolftown.lauschig.settings.AccountSectionViewModel
import io.wolftown.lauschig.tv.TvSettingsStore
import io.wolftown.lauschig.tv.rememberTvSettings
import io.wolftown.lauschig.ui.AppViewModel
import io.wolftown.lauschig.ui.connection.ConnectionStatusViewModel
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.settings.SignOutStep
import io.wolftown.lauschig.tv.HoldsAQuestion
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.nav.KeepRailClosed
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.tv.nav.LocalRailHasFocus
import io.wolftown.lauschig.tv.profiles.PinPage
import io.wolftown.lauschig.tv.profiles.TvPinPad
import io.wolftown.lauschig.tv.profiles.TvSignInDialogs
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.ProtectionStep
import io.wolftown.lauschig.ui.profiles.label

/** A row is tagged with this plus its key ([ROW_PROFILE] …). */
internal const val TAG_SETTINGS_ROW = "tvSettings:"
internal const val ROW_PROFILE = "profile"
internal const val ROW_PROTECT = "protect"
internal const val ROW_SCREEN_OFF = "screenOff"
internal const val ROW_CONNECTION = "connection"
internal const val ROW_SIGN_OUT = "signOut"
internal const val ROW_ABOUT = "about"

/** A choice of "screen off after" is tagged with this plus its minutes. */
internal const val TAG_SCREEN_OFF_OPTION = "tvScreenOff:"
internal const val TAG_SCREEN_OFF_DIALOG = "tvScreenOffDialog"
internal const val TAG_PROTECT_DIALOG = "tvProtectDialog"
internal const val TAG_PROTECT_SET = "tvProtectSet"
internal const val TAG_PROTECT_REMOVE = "tvProtectRemove"
internal const val TAG_PROTECT_CANCEL = "tvProtectCancel"
internal const val TAG_PROTECT_FAILED = "tvProtectFailed"
internal const val TAG_NEW_PIN_TITLE = "tvNewPinTitle"
internal const val TAG_NEW_PIN_STEP = "tvNewPinStep"
internal const val TAG_SIGN_OUT_DIALOG = "tvSignOutDialog"
internal const val TAG_SIGN_OUT_CONFIRM = "tvSignOutConfirm"
internal const val TAG_SIGN_OUT_CANCEL = "tvSignOutCancel"
internal const val TAG_SIGN_OUT_UNSYNCED = "tvSignOutUnsynced"
internal const val TAG_RECONNECT_RESULT = "tvReconnectResult"
internal const val TAG_NEW_PIN_SAVING = "tvNewPinSaving"

/** "Screen off after": never (0), or after so many minutes without a key press. */
internal val SCREEN_OFF_CHOICES = listOf(0, 1, 5, 15)

/** What the settings page shows. [host] and [profileName]: of the signed-in account; [checking]: a connection check runs. */
@Immutable
data class TvSettingsState(
    val profileName: String?,
    val host: String?,
    val online: Boolean,
    val checking: Boolean,
    val protection: ProfileLockKind,
    val moreProfiles: Boolean,
    val screenOffMinutes: Int,
    val versionName: String,
    val versionCode: Int,
    /** "Reconnect" ran or runs: its result is said once more, also when the connection was up before. */
    val reconnect: ReconnectState? = null,
) {
    /** As on the phone: one profile needs no protection, but one that has it can still take it off. */
    val showsProtection: Boolean get() = moreProfiles || protection != ProfileLockKind.NONE
}

/**
 * The TV's few settings (TV spec, "Einstellungen"): switch profile, protect it with a PIN (only a PIN, TV spec E12), when the
 * player goes black, the connection, sign out and the version. The focus starts on the first row and comes back to the row it
 * was on after a dialog or the PIN pages.
 *
 * A PIN is set through the profiles' protection flow: [onProtect] passes the current protection first ([gate]; a PIN is asked
 * on a page of its own, the way of a forgotten one is [onSignIn]), then [protectionStep] says what comes next: the choice
 * (set or change the PIN [onChoosePin], remove it [onRemovePin]), then the new PIN, entered twice, to [onSetPin].
 * [onCancelProtection] ends the flow wherever it is.
 */
@Composable
fun TvSettingsScreen(
    state: TvSettingsState,
    signOut: SignOutStep?,
    onSwitchProfile: () -> Unit,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onScreenOff: (Int) -> Unit,
    onReconnect: () -> Unit,
    onSignOut: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onCancelSignOut: () -> Unit,
    gate: GatePrompt? = null,
    protectionStep: ProtectionStep? = null,
    onProtect: () -> Unit = {},
    onChoosePin: () -> Unit = {},
    onGatePin: (String) -> Unit = {},
    onSignIn: (serverId: String) -> Unit = {},
    onCancelProtection: () -> Unit = {},
) {
    var choosingScreenOff by rememberSaveable { mutableStateOf(false) }
    // A PIN or protection step: requests from outside (voice, the player) wait for it, and the rail stays shut while a PIN is typed.
    HoldsAQuestion(gate != null || protectionStep != null)
    KeepRailClosed(gate is GatePrompt.Pin || protectionStep is ProtectionStep.NewPin)
    // Kept above the list, which leaves while a PIN page is on: back from it, the focus is on the row it came from.
    val rowFocus = RowFocus(rememberSaveable { mutableStateOf(ROW_PROFILE) }, rememberSaveable { mutableIntStateOf(0) })
    when {
        gate is GatePrompt.Pin -> PinPage(gate, onGatePin, onCancelProtection, onForgot = { onSignIn(gate.row.serverId) })
        protectionStep is ProtectionStep.NewPin -> NewPinPage(protectionStep, onSetPin, onCancelProtection)
        else -> SettingsRows(state, rowFocus, onSwitchProfile, onProtect, onScreenOffRow = { choosingScreenOff = true }, onReconnect, onSignOut)
    }
    TvSignInDialogs(gate, onSignIn, onCancelProtection)
    (protectionStep as? ProtectionStep.Choose)?.let { ProtectDialog(it, onChoosePin, onRemovePin, onCancelProtection) }
    if (choosingScreenOff) {
        ScreenOffDialog(
            state.screenOffMinutes,
            onChoose = {
                choosingScreenOff = false
                onScreenOff(it)
            },
            onCancel = { choosingScreenOff = false },
        )
    }
    signOut?.let { SignOutDialog(it, state.host, onConfirmSignOut, onCancelSignOut) }
}

/**
 * The settings page's data. The account and the sign-out come from the phone's account section, the protection from the
 * profiles, the connection from the app and the connection status ("Reconnect"), "screen off" from the TV's own settings.
 * [onSwitchProfile] opens "who is listening?"; [onSignIn] the sign-in of an account (the way of a forgotten PIN).
 */
@Composable
fun TvSettingsRoute(
    onSwitchProfile: () -> Unit,
    onSignIn: (serverId: String) -> Unit,
    app: AppViewModel = hiltViewModel(),
    account: AccountSectionViewModel = hiltViewModel(),
    profiles: ProfilesViewModel = hiltViewModel(),
    connection: ConnectionStatusViewModel = hiltViewModel(),
) {
    val info by account.account.collectAsStateWithLifecycle()
    val signOut by account.step.collectAsStateWithLifecycle()
    val online by app.connection.collectAsStateWithLifecycle()
    val firstCheck by app.checking.collectAsStateWithLifecycle()
    val status by connection.state.collectAsStateWithLifecycle()
    val rows by profiles.profiles.collectAsStateWithLifecycle()
    val gate by profiles.gatePrompt.collectAsStateWithLifecycle()
    val protection by profiles.protection.collectAsStateWithLifecycle()
    val settings = rememberTvSettings()
    val screenOff by settings.screenOffMinutes.collectAsStateWithLifecycle(TvSettingsStore.DEFAULT_SCREEN_OFF_MINUTES)
    val scope = rememberCoroutineScope()
    val cancelProtection = { endProtection(profiles) }
    TvSettingsScreen(
        state = TvSettingsState(
            profileName = info?.username,
            host = info?.host,
            online = online is ConnectionState.Online,
            checking = firstCheck || status.reconnect == ReconnectState.Running,
            reconnect = status.reconnect,
            protection = rows?.firstOrNull { it.active }?.protection ?: ProfileLockKind.NONE,
            moreProfiles = (rows?.size ?: 0) > 1,
            screenOffMinutes = screenOff,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        ),
        signOut = signOut,
        onSwitchProfile = onSwitchProfile,
        onSetPin = profiles::setNewPin,
        onRemovePin = { profiles.chooseProtection(ProfileLockKind.NONE) },
        onScreenOff = screenOffSetter(settings, scope),
        onReconnect = connection::reconnect,
        onSignOut = account::startSignOut,
        onConfirmSignOut = account::confirm,
        onCancelSignOut = account::cancel,
        gate = gate,
        protectionStep = protection,
        onProtect = profiles::changeProtection,
        onChoosePin = { profiles.chooseProtection(ProfileLockKind.PIN) },
        onGatePin = profiles::submitPin,
        onSignIn = { serverId -> leaveForSignIn(profiles, serverId, onSignIn) },
        onCancelProtection = cancelProtection,
    )
}

/** Ends the protection flow wherever it stands: the current PIN's question or the steps after it. */
internal fun endProtection(profiles: ProfilesViewModel) {
    profiles.cancelGate()
    profiles.cancelProtection()
}

/** To the sign-in of [serverId] (a forgotten PIN): the flow ends first, so nothing of it waits behind the sign-in. */
internal fun leaveForSignIn(profiles: ProfilesViewModel, serverId: String, onSignIn: (String) -> Unit) {
    endProtection(profiles)
    onSignIn(serverId)
}

/** "Screen off after": the minutes chosen go to the TV's settings, where the player reads them. */
internal fun screenOffSetter(settings: TvSettingsStore, scope: CoroutineScope): (Int) -> Unit = { minutes ->
    scope.launch { settings.setScreenOffMinutes(minutes) }
}

/** Which row had the focus last, by key and by place. */
private class RowFocus(val key: MutableState<String>, val index: MutableIntState)

/** One row of the list. */
private class SettingsRow(val key: String, val icon: ImageVector, val headline: String, val supporting: String?, val trailing: String? = null, val onClick: () -> Unit)

@Composable
private fun SettingsRows(
    state: TvSettingsState,
    focus: RowFocus,
    onSwitchProfile: () -> Unit,
    onProtect: () -> Unit,
    onScreenOffRow: () -> Unit,
    onReconnect: () -> Unit,
    onSignOut: () -> Unit,
) {
    val connectionStatus = stringResource(
        when {
            state.checking -> R.string.connection_state_checking
            state.online -> R.string.tv_settings_connected
            else -> R.string.connection_state_offline
        },
    )
    val rows = listOfNotNull(
        SettingsRow(
            ROW_PROFILE, Icons.Rounded.People, stringResource(R.string.tv_settings_switch_profile),
            state.profileName?.let { stringResource(R.string.tv_settings_profile_active, it) } ?: stringResource(R.string.tv_settings_profile_hint),
            onClick = onSwitchProfile,
        ),
        SettingsRow(ROW_PROTECT, Icons.Rounded.Lock, stringResource(R.string.profiles_protect), stringResource(state.protection.label()), onClick = onProtect)
            .takeIf { state.showsProtection },
        SettingsRow(
            ROW_SCREEN_OFF, Icons.Rounded.Tv, stringResource(R.string.tv_settings_screen_off), screenOffText(state.screenOffMinutes),
            onClick = onScreenOffRow,
        ),
        SettingsRow(
            ROW_CONNECTION, Icons.Rounded.Wifi, stringResource(R.string.settings_section_connection),
            state.host?.let { stringResource(R.string.tv_settings_connection_line, it, connectionStatus) } ?: connectionStatus,
            trailing = stringResource(R.string.connection_reconnect),
            onClick = onReconnect,
        ),
        SettingsRow(ROW_SIGN_OUT, Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.sign_out), stringResource(R.string.tv_settings_sign_out_hint), onClick = onSignOut),
        // Nothing to do here, but in the list like the rest, so that the version is read out when the focus reaches it.
        SettingsRow(
            ROW_ABOUT, Icons.Rounded.Info, stringResource(R.string.settings_section_about),
            stringResource(R.string.tv_settings_version, state.versionName, state.versionCode), onClick = {},
        ),
    )
    val keys = rows.map { it.key }
    var focused by focus.key
    var focusedIndex by focus.index
    // The row with the focus can go (the protection row, once a single profile's PIN is removed): the one now in its place takes it.
    val target = focused.takeIf { it in keys } ?: keys[focusedIndex.coerceIn(0, keys.lastIndex)]
    val requester = remember { FocusRequester() }
    val railHasFocus = LocalRailHasFocus.current
    LaunchedEffect(target) { if (!railHasFocus()) requester.requestFocusWhenPlaced() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        rows.forEachIndexed { index, row ->
            ListItem(
                selected = false,
                onClick = row.onClick,
                headlineContent = { Text(row.headline) },
                supportingContent = row.supporting?.let { { Text(it) } },
                leadingContent = { Icon(row.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                trailingContent = row.trailing?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .then(if (row.key == target) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focused = row.key
                            focusedIndex = index
                        }
                    }
                    .testTag(TAG_SETTINGS_ROW + row.key),
            )
            if (row.key == ROW_CONNECTION) {
                // Always there, so that a new result is read out (AnnouncedLine), even if the line above did not change.
                AnnouncedLine(
                    when (val r = state.reconnect) {
                        null -> null
                        ReconnectState.Running -> stringResource(R.string.connection_reconnecting)
                        is ReconnectState.Done -> stringResource(if (r.online) R.string.connection_reconnect_online else R.string.tv_settings_reconnect_offline)
                    },
                    tag = TAG_RECONNECT_RESULT,
                    modifier = Modifier.widthIn(max = 720.dp).padding(start = 72.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun screenOffText(minutes: Int): String =
    if (minutes <= 0) stringResource(R.string.tv_settings_screen_off_never)
    else pluralStringResource(R.plurals.tv_settings_screen_off_minutes, minutes, minutes)

/** The choices as a list, the focus on the one that applies; OK takes one and closes. */
@Composable
private fun ScreenOffDialog(current: Int, onChoose: (Int) -> Unit, onCancel: () -> Unit) {
    // A value the TV no longer offers: the focus on the default, which is where most people want to be.
    val start = SCREEN_OFF_CHOICES.indexOf(current).takeIf { it >= 0 } ?: SCREEN_OFF_CHOICES.indexOf(DEFAULT_CHOICE)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(stringResource(R.string.tv_settings_screen_off)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SCREEN_OFF_CHOICES.forEachIndexed { i, minutes ->
                    ListItem(
                        selected = minutes == current,
                        onClick = { onChoose(minutes) },
                        headlineContent = { Text(screenOffText(minutes)) },
                        // Said, not only seen: which of them applies now.
                        modifier = Modifier
                            .semantics { selected = minutes == current }
                            .then(if (i == start) Modifier.focusRequester(focus) else Modifier)
                            .testTag(TAG_SCREEN_OFF_OPTION + minutes),
                    )
                }
                Text(
                    stringResource(R.string.tv_settings_screen_off_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // Here, in the dialog's own composition: only once the list exists can the focus be asked for.
            LaunchedEffect(Unit) { focus.requestFocusWhenPlaced() }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.setup_cancel)) } },
        modifier = Modifier.testTag(TAG_SCREEN_OFF_DIALOG),
    )
}

private const val DEFAULT_CHOICE = 5

/** After the current protection was passed: set (or change) the PIN, or take it off. The device lock is not offered (TV spec E12). */
@Composable
private fun ProtectDialog(step: ProtectionStep.Choose, onChoosePin: () -> Unit, onRemovePin: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(stringResource(R.string.profiles_protect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.profiles_protect_note), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onChoosePin, modifier = Modifier.focusRequester(focus).testTag(TAG_PROTECT_SET)) {
                        Text(stringResource(if (step.current == ProfileLockKind.PIN) R.string.tv_settings_change_pin else R.string.tv_settings_set_pin))
                    }
                    if (step.current != ProfileLockKind.NONE) {
                        OutlinedButton(onClick = onRemovePin, modifier = Modifier.testTag(TAG_PROTECT_REMOVE)) {
                            Text(stringResource(R.string.tv_settings_remove_pin))
                        }
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(TAG_PROTECT_CANCEL)) { Text(stringResource(R.string.setup_cancel)) }
                }
                AnnouncedLine(
                    if (step.saveFailed) stringResource(R.string.profiles_protect_save_failed) else null,
                    tag = TAG_PROTECT_FAILED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LaunchedEffect(Unit) { focus.requestFocusWhenPlaced() }
        },
        confirmButton = {},
        modifier = Modifier.testTag(TAG_PROTECT_DIALOG),
    )
}

/**
 * The new PIN, entered twice on the pad (as on the phone): the first entry is held here until the second one matches; two
 * different entries start again. Only the matching one leaves this page.
 */
@Composable
private fun NewPinPage(step: ProtectionStep.NewPin, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var differed by remember { mutableStateOf(false) }
    val submit = { entered: String ->
        val earlier = first
        when {
            step.saving -> Unit
            earlier == null -> {
                first = entered
                differed = false
            }
            earlier == entered -> onDone(entered)
            else -> {
                first = null
                differed = true
            }
        }
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal / 2),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.overscanHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                stringResource(R.string.pin_new_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag(TAG_NEW_PIN_TITLE).semantics { heading() },
            )
            // Which of the two entries this is; read out when it changes.
            AnnouncedLine(
                stringResource(if (first == null) R.string.pin_new_hint else R.string.pin_new_again),
                tag = TAG_NEW_PIN_STEP,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.saving) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
            // Why the pad does not answer while it saves.
            AnnouncedLine(
                if (step.saving) stringResource(R.string.tv_settings_pin_saving) else null,
                tag = TAG_NEW_PIN_SAVING,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TvPinPad(
                notice = if (differed) stringResource(R.string.pin_new_differs) else null,
                waitMs = 0,
                checking = step.saving,
                onSubmit = submit,
                onCancel = { if (!step.saving) onCancel() },
            )
        }
    }
}

/**
 * The question before signing out, the TV's way: no downloads to keep (the TV has none), so no checkbox; changes not on the
 * server yet are named, and they stay on the TV for the next sign-in of this account (the view model's default). The focus
 * starts on "Cancel". While it signs out nothing can be pressed, Back included.
 */
@Composable
private fun SignOutDialog(step: SignOutStep, host: String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val running = step == SignOutStep.SigningOut
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { if (!running) onCancel() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
        title = { androidx.compose.material3.Text(stringResource(if (running) R.string.sign_out_running else R.string.sign_out_title)) },
        text = {
            when (step) {
                SignOutStep.Checking -> Busy(stringResource(R.string.sign_out_syncing))
                SignOutStep.SigningOut -> Busy(null)
                is SignOutStep.Confirm -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (host.isNullOrBlank()) stringResource(R.string.tv_sign_out_message_no_host) else stringResource(R.string.sign_out_message, host),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val unsynced = step.unsynced
                    if (unsynced == null || unsynced > 0) {
                        Text(
                            if (unsynced == null) stringResource(R.string.sign_out_unsynced_unknown)
                            else pluralStringResource(if (step.keeps) R.plurals.sign_out_unsynced_kept else R.plurals.sign_out_unsynced_lost, unsynced, unsynced),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (step.keeps) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(TAG_SIGN_OUT_UNSYNCED),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = step is SignOutStep.Confirm, modifier = Modifier.testTag(TAG_SIGN_OUT_CONFIRM)) {
                Text(stringResource(R.string.sign_out_confirm))
            }
        },
        dismissButton = {
            LaunchedEffect(Unit) { cancelFocus.requestFocusWhenPlaced() }
            OutlinedButton(onClick = onCancel, enabled = !running, modifier = Modifier.focusRequester(cancelFocus).testTag(TAG_SIGN_OUT_CANCEL)) {
                Text(stringResource(R.string.sign_out_cancel))
            }
        },
        modifier = Modifier.testTag(TAG_SIGN_OUT_DIALOG),
    )
}

@Composable
private fun Busy(text: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
