package io.wolftown.lauschig.ui.profiles

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.theme.initialsOf
import io.wolftown.lauschig.ui.theme.placeholderToneFor
import kotlinx.coroutines.delay

/** What the avatar shows: the initials, else the first character of the name. */
fun profileInitials(name: String): String =
    initialsOf(name).ifEmpty { name.trim().take(1).uppercase().ifEmpty { "?" } }

/** The initials on a colour that stays with the [serverId], the same on every start. Decoration: the row says the name. */
@Composable
fun ProfileAvatar(name: String, serverId: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tone = placeholderToneFor(serverId, MaterialTheme.colorScheme)
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(tone.start, tone.end))),
        contentAlignment = Alignment.Center,
    ) {
        Text(profileInitials(name), color = tone.content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** The start page's way to the sheet, beside the gear; nothing with a single profile. */
@Composable
fun ProfileHeaderButton(rows: List<ProfileRow>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = headerProfile(rows) ?: return
    val label = stringResource(R.string.profiles_header, active.name)
    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = label }.testTag("profileAvatar")) {
        ProfileAvatar(active.name, active.serverId, size = 28.dp)
    }
}

/** „Profile“ as a sheet over whatever page opened it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(rows: List<ProfileRow>, onSwitch: (ProfileRow) -> Unit, onRemove: (ProfileRow) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ProfilesSheetContent(rows, onSwitch, onRemove, onAdd)
    }
}

@Composable
fun ProfilesSheetContent(rows: List<ProfileRow>, onSwitch: (ProfileRow) -> Unit, onRemove: (ProfileRow) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l).testTag("profilesSheet")) {
        Text(
            stringResource(R.string.profiles_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
        )
        ProfileList(rows, onSwitch, onRemove, onAdd)
    }
}

/** The profiles, then „Profil hinzufügen“: the sheet's content and the picker's. */
@Composable
fun ProfileList(rows: List<ProfileRow>, onSwitch: (ProfileRow) -> Unit, onRemove: (ProfileRow) -> Unit, onAdd: () -> Unit) {
    rows.forEach { row -> ProfileListRow(row, onSwitch, onRemove) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.profiles_add)) },
        leadingContent = { Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(40.dp).padding(Spacing.s)) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAdd).testTag("addProfile"),
    )
}

@Composable
private fun ProfileListRow(row: ProfileRow, onSwitch: (ProfileRow) -> Unit, onRemove: (ProfileRow) -> Unit) {
    ListItem(
        headlineContent = { Text(row.name) },
        supportingContent = if (row.host != null || row.needsReauth) {
            {
                Column {
                    row.host?.let { Text(it) }
                    if (row.needsReauth) Text(stringResource(R.string.profiles_needs_reauth), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
        leadingContent = { ProfileAvatar(row.name, row.serverId) },
        trailingContent = {
            if (row.active) Icon(Icons.Rounded.Check, stringResource(R.string.profiles_active), tint = MaterialTheme.colorScheme.primary)
            else ProfileMenu(row, onRemove)
        },
        modifier = Modifier.fillMaxWidth().selectable(selected = row.active, role = Role.Button, onClick = { onSwitch(row) }).testTag("profile:${row.name}"),
    )
}

@Composable
private fun ProfileMenu(row: ProfileRow, onRemove: (ProfileRow) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.profiles_more, row.name)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profiles_remove)) },
                onClick = {
                    open = false
                    onRemove(row)
                },
            )
        }
    }
}

/** From here on „Wechsle zu …“ says that it takes longer than usual: the worst case is some 20 s. */
const val PROFILE_BUSY_SLOW_MS = 5_000L

/**
 * While an operation runs, over everything: it takes no input and no "back", so nothing starts on top of it and no page of
 * the account being left is tapped. It stays up through the moment the next account's pages appear underneath.
 */
@Composable
fun ProfileBusyDialog(work: ProfileWork) {
    var slow by remember(work) { mutableStateOf(false) }
    LaunchedEffect(work) {
        delay(PROFILE_BUSY_SLOW_MS)
        slow = true
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("profileBusy")) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(
                        stringResource(
                            when (work) {
                                is ProfileWork.Switching -> R.string.profiles_switching
                                is ProfileWork.Removing -> R.string.profiles_removing
                            },
                            work.name,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = Spacing.l).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (slow) {
                    Text(
                        stringResource(R.string.profiles_slow),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("profileBusySlow"),
                    )
                }
            }
        }
    }
}

/** „Entfernen“: what goes with the profile, then the answer. */
@Composable
fun ProfileRemoveDialog(question: RemovalQuestion, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val facts = question.facts
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.profiles_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.profiles_remove_message, question.row.name))
                if (facts == null) {
                    Text(stringResource(R.string.profiles_remove_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (facts.downloadBytes > 0) {
                        val size = Formatter.formatShortFileSize(LocalContext.current, facts.downloadBytes).replace(' ', ' ')
                        Text(stringResource(R.string.profiles_remove_downloads, size), modifier = Modifier.testTag("removeDownloads"))
                    }
                    if (facts.onMissingCard) {
                        Text(stringResource(R.string.sign_out_card_missing_deleted), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("removeCardMissing"))
                    }
                    val unsynced = facts.unsynced
                    if (unsynced == null) {
                        Text(stringResource(R.string.sign_out_unsynced_unknown), color = MaterialTheme.colorScheme.error)
                    } else if (unsynced > 0) {
                        Text(pluralStringResource(R.plurals.sign_out_unsynced_lost, unsynced, unsynced), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("removeUnsynced"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = facts != null, modifier = Modifier.testTag("confirmRemove")) {
                Text(stringResource(R.string.profiles_remove))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.sign_out_cancel)) } },
        modifier = Modifier.testTag("removeDialog"),
    )
}

/** The snackbar after an operation. */
@Composable
fun profileOutcomeText(outcome: ProfileOutcome): String = stringResource(
    when (outcome.kind) {
        ProfileOutcome.Kind.SWITCHED -> R.string.profiles_switched
        ProfileOutcome.Kind.SWITCH_FAILED -> R.string.profiles_switch_failed
        ProfileOutcome.Kind.REMOVED -> R.string.profiles_removed
        ProfileOutcome.Kind.REMOVE_FAILED -> R.string.profiles_remove_failed
    },
    outcome.name,
)
