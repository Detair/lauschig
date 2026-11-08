package io.wolftown.lauschig.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.AnnouncedLine
import io.wolftown.lauschig.ui.common.PasswordField

@Composable
fun SetupRoute(onDone: () -> Unit, modifier: Modifier = Modifier, onBack: () -> Unit = {}, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enableSsidBinding() else viewModel.ssidPermissionDenied()
    }
    // Open the identity provider in a Custom Tab; the server sends the user back via lauschig://oauth (MainActivity).
    LaunchedEffect(state.ssoLaunchUrl) {
        val url = state.ssoLaunchUrl ?: return@LaunchedEffect
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            viewModel.onSsoLaunched()
        } catch (_: ActivityNotFoundException) {
            viewModel.onSsoLaunchFailed()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SetupScreen(
        state = state,
        onLocal = viewModel::onLocalChanged,
        onPublic = viewModel::onPublicChanged,
        onUsername = viewModel::onUsernameChanged,
        onPassword = viewModel::onPasswordChanged,
        onSsidBinding = { on ->
            when {
                !on -> viewModel.disableSsidBinding()
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED -> viewModel.enableSsidBinding()
                else -> permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        },
        onLogin = viewModel::login,
        onSso = viewModel::loginWithSso,
        onConfirmCleartext = viewModel::confirmCleartext,
        onDismissCleartext = viewModel::dismissCleartext,
        modifier = modifier,
        onBack = onBack,
    )
}

@Composable
fun SetupScreen(
    state: SetupUiState,
    onLocal: (String) -> Unit,
    onPublic: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSsidBinding: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onSso: () -> Unit,
    onConfirmCleartext: () -> Unit,
    onDismissCleartext: () -> Unit,
    modifier: Modifier = Modifier,
    /** „Profil hinzufügen“ only: back to the active account's pages. */
    onBack: () -> Unit = {},
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("setupScreen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.addProfile || state.unlockServerId != null) {
            // The arrow's own inset makes it line up with the text below.
            Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
                Text(stringResource(if (state.unlockServerId != null) R.string.setup_unlock_title else R.string.profiles_add), style = MaterialTheme.typography.headlineSmall)
            }
            when {
                state.unlockServerId != null -> stringResource(R.string.setup_unlock_intro, state.unlockName ?: state.username)
                state.nobodyActive == true -> stringResource(R.string.setup_add_intro_nobody)
                state.nobodyActive == false -> stringResource(R.string.setup_add_intro)
                else -> null
            }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        } else {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            state.local, onLocal, Modifier.fillMaxWidth().testTag("setupLocal"), enabled = !state.addressesFixed, singleLine = true,
            label = { Text(stringResource(R.string.setup_local_address)) },
            placeholder = { Text(stringResource(R.string.setup_local_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            state.public, onPublic, Modifier.fillMaxWidth().testTag("setupPublic"), enabled = !state.addressesFixed, singleLine = true,
            label = { Text(stringResource(R.string.setup_public_address)) },
            placeholder = { Text(stringResource(R.string.setup_public_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.ssidBinding,
                onCheckedChange = onSsidBinding,
                enabled = state.local.isNotBlank() && !state.addressesFixed,
                modifier = Modifier.testTag("ssidCheckbox"),
            )
            Text(stringResource(R.string.setup_ssid_binding), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.ssidBinding && state.currentSsid != null) {
            AssistChip(onClick = {}, label = { Text(state.currentSsid) })
        }
        if (state.ssidHint) {
            Text(stringResource(R.string.setup_ssid_unreadable), style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            state.username, onUsername, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.setup_username)) },
        )
        PasswordField(
            state.password, onPassword,
            label = { Text(stringResource(R.string.setup_password)) },
            modifier = Modifier.fillMaxWidth().testTag("setupPassword"),
        )

        // One group: no gap for an error that is not there.
        Column {
            AnnouncedLine(
                state.error?.let { stringResource(it.message()) },
                tag = "setupError",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                padding = PaddingValues(bottom = 12.dp),
            )
            Button(onClick = onLogin, enabled = state.canLogin, modifier = Modifier.fillMaxWidth().testTag("loginButton")) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.setup_login))
            }
        }
        if (state.ssoAvailable) {
            OutlinedButton(
                onClick = onSso,
                enabled = !state.loading && (state.local.isNotBlank() || state.public.isNotBlank()),
                modifier = Modifier.fillMaxWidth().testTag("ssoButton"),
            ) {
                Text(state.ssoButtonText ?: stringResource(R.string.setup_sso_default))
            }
        }
    }

    state.cleartextPrompt?.let { hosts ->
        AlertDialog(
            onDismissRequest = onDismissCleartext,
            title = { Text(stringResource(R.string.setup_cleartext_title)) },
            text = { Text(stringResource(R.string.setup_cleartext_message, hosts.joinToString("\n"))) },
            confirmButton = { TextButton(onClick = onConfirmCleartext) { Text(stringResource(R.string.setup_cleartext_confirm)) } },
            dismissButton = { TextButton(onClick = onDismissCleartext) { Text(stringResource(R.string.setup_cancel)) } },
        )
    }
}

private fun SetupError.message(): Int = when (this) {
    SetupError.INVALID_ADDRESS -> R.string.setup_error_invalid_address
    SetupError.UNREACHABLE -> R.string.setup_error_unreachable
    SetupError.WRONG_LOGIN -> R.string.setup_error_wrong_login
    SetupError.SERVER_TOO_OLD -> R.string.setup_error_server_too_old
    SetupError.SAVE_FAILED -> R.string.setup_error_save_failed
    SetupError.SSO_REDIRECT_NOT_ALLOWED -> R.string.setup_error_sso_redirect
    SetupError.SSO_FAILED -> R.string.setup_error_sso_failed
    SetupError.UNLOCK_OTHER_ACCOUNT -> R.string.setup_error_unlock_other
}
