package io.wolftown.lauschig.ui.tvsetup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.serverqr.ServerQrScanner
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.common.PasswordField
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun TvHandoverRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: TvHandoverViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The camera is asked for when the button is tapped, never before, as in the setup form.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startScan() else viewModel.cameraRefused()
    }
    if (step == TvHandoverStep.Scanning) {
        // Whatever code the camera finds closes the camera; the view model decides what it was.
        ServerQrScanner(
            onCode = viewModel::onScanned,
            onClose = viewModel::scanClosed,
            modifier = modifier,
            hint = R.string.tvsetup_scan_hint,
            noCameraHint = R.string.tvsetup_scan_no_camera,
        )
        return
    }
    TvHandoverScreen(
        step,
        onBack = onBack,
        onScan = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) viewModel.startScan()
            else camera.launch(Manifest.permission.CAMERA)
        },
        onPassword = viewModel::onPassword,
        onSend = viewModel::send,
        onDone = onBack,
        onAgain = viewModel::again,
        modifier = modifier,
    )
}

@Composable
fun TvHandoverScreen(
    step: TvHandoverStep,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPassword: (String) -> Unit,
    onSend: () -> Unit,
    onDone: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().testTag("tvSetupScreen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.tvsetup_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                is TvHandoverStep.Intro -> Intro(step.note, onScan)
                // The route shows the camera instead; nothing to tap here meanwhile.
                TvHandoverStep.Scanning -> Intro(null, onScan)
                is TvHandoverStep.Confirm -> Confirm(step, onPassword, onSend)
                TvHandoverStep.Done -> {
                    Text(stringResource(R.string.tvsetup_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tvSetupDoneText"))
                    Button(onClick = onDone, modifier = Modifier.testTag("tvSetupDone")) { Text(stringResource(R.string.tvsetup_done_button)) }
                }
                is TvHandoverStep.Failed -> {
                    AnnouncedLine(stringResource(errorText(step.reason)), tag = "tvSetupFailed", style = MaterialTheme.typography.titleMedium)
                    if (step.sessionMayBeOpen) {
                        Text(stringResource(R.string.tvsetup_session_may_be_open), modifier = Modifier.testTag("tvSetupSessionMayBeOpen"))
                    }
                    Button(onClick = onAgain, modifier = Modifier.testTag("tvSetupAgain")) { Text(stringResource(R.string.tvsetup_again)) }
                }
            }
        }
    }
}

@Composable
private fun Intro(note: TvHandoverNote?, onScan: () -> Unit) {
    Text(stringResource(R.string.tvsetup_intro_scan))
    Text(stringResource(R.string.tvsetup_intro_password))
    Text(stringResource(R.string.tvsetup_intro_own))
    AnnouncedLine(note?.let { stringResource(noteText(it)) }, tag = "tvSetupNote", color = MaterialTheme.colorScheme.error)
    Button(onClick = onScan, modifier = Modifier.testTag("tvSetupScan")) { Text(stringResource(R.string.tvsetup_scan)) }
}

@Composable
private fun Confirm(step: TvHandoverStep.Confirm, onPassword: (String) -> Unit, onSend: () -> Unit) {
    val number = grouped(step.number)
    Text(
        stringResource(R.string.tvsetup_confirm_question, number, serverName(step.servers), step.username),
        style = MaterialTheme.typography.titleMedium,
    )
    // Read digit by digit, as on the TV: "forty-eight twenty-one" would not match what the TV says.
    val spoken = stringResource(R.string.tvsetup_number_description, step.number.toList().joinToString(" "))
    Text(
        number,
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.testTag("tvSetupNumber").semantics { contentDescription = spoken },
    )
    Text(stringResource(R.string.tvsetup_confirm_compare))
    Text(stringResource(R.string.tvsetup_servers), style = MaterialTheme.typography.labelLarge)
    step.servers.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    PasswordField(
        value = step.password,
        onValueChange = onPassword,
        label = { Text(stringResource(R.string.tvsetup_password)) },
        modifier = Modifier.fillMaxWidth().testTag("tvSetupPassword"),
    )
    Text(stringResource(R.string.tvsetup_sso_hint), style = MaterialTheme.typography.bodySmall)
    AnnouncedLine(step.error?.let { stringResource(errorText(it)) }, tag = "tvSetupError", color = MaterialTheme.colorScheme.error)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSend, enabled = step.password.isNotEmpty() && !step.working, modifier = Modifier.testTag("tvSetupSend")) {
            Text(stringResource(R.string.tvsetup_send))
        }
        if (step.working) {
            CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp))
            Text(stringResource(R.string.tvsetup_working), modifier = Modifier.padding(start = 8.dp))
        }
    }
    // The TV asks before it uses the packet (spec E17); without this the phone would just seem to hang for up to a minute.
    if (step.working) Text(stringResource(R.string.tvsetup_confirm_on_tv), modifier = Modifier.testTag("tvSetupConfirmOnTv"))
}

/** "48 21": the same two groups the TV shows. */
private fun grouped(number: String): String = "${number.take(2)} ${number.drop(2)}"

/** The server as the account names it elsewhere: the public address's host, else the local one's (servers are local first). */
private fun serverName(servers: List<String>): String = servers.lastOrNull()?.let { it.toHttpUrlOrNull()?.host ?: it }.orEmpty()

private fun noteText(note: TvHandoverNote): Int = when (note) {
    TvHandoverNote.CAMERA_REFUSED -> R.string.tvsetup_note_camera_refused
    TvHandoverNote.NOT_A_TV_CODE -> R.string.tvsetup_note_not_a_tv_code
    TvHandoverNote.SERVER_CODE -> R.string.tvsetup_note_server_code
    TvHandoverNote.OTHER_VERSION -> R.string.tvsetup_note_other_version
    TvHandoverNote.NOT_HOME_NETWORK -> R.string.tvsetup_note_not_home_network
}

private fun errorText(error: TvHandoverError): Int = when (error) {
    TvHandoverError.WRONG_PASSWORD -> R.string.tvsetup_error_wrong_password
    TvHandoverError.SERVER_UNREACHABLE -> R.string.tvsetup_error_server_unreachable
    TvHandoverError.NO_HOME_NETWORK -> R.string.tvsetup_error_no_home_network
    TvHandoverError.TV_UNREACHABLE -> R.string.tvsetup_error_tv_unreachable
    TvHandoverError.CODE_EXPIRED -> R.string.tvsetup_error_code_expired
    TvHandoverError.TV_CANNOT_REACH_SERVER -> R.string.tvsetup_error_tv_cannot_reach_server
    TvHandoverError.TV_REFUSED -> R.string.tvsetup_error_tv_refused
    TvHandoverError.DECLINED_ON_TV -> R.string.tvsetup_error_declined_on_tv
    TvHandoverError.NO_REPLY -> R.string.tvsetup_error_no_reply
    TvHandoverError.FAILED -> R.string.tvsetup_error_failed
}
