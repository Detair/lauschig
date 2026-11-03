package io.wolftown.lauschig.links

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The hosts whose web links the manifest declares (Gradle property `lauschig.linkHosts`). */
val LINK_HOSTS: List<String> = BuildConfig.LINK_HOSTS.split(',').filter { it.isNotBlank() }

@HiltViewModel
class LinkSettingsViewModel @Inject constructor(accounts: AccountStore) : ViewModel() {
    /** Host names of the signed-in account's addresses. */
    val serverHosts: StateFlow<List<String>> = accounts.account
        .map { a -> listOfNotNull(a?.addresses?.public?.host, a?.addresses?.local?.host).distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** The declared hosts the user allowed under "Open by default" (Android 12+; null below, where Android asks per link). */
fun allowedLinkHosts(context: Context): Set<String>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val state = try {
        context.getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(context.packageName)
    } catch (e: Exception) {
        null
    } ?: return emptySet()
    return state.hostToStateMap.filterValues { it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED }.keys
}

/** Android's page where the user allows the hosts ("Open by default"); app details below Android 12. */
fun openByDefaultIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${context.packageName}".toUri())
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

@Composable
fun LinksSection(viewModel: LinkSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val servers by viewModel.serverHosts.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(allowedLinkHosts(context)) }
    // Back from the system settings: read the choice again.
    LifecycleResumeEffect(Unit) {
        allowed = allowedLinkHosts(context)
        onPauseOrDispose { }
    }
    LinksSectionContent(LINK_HOSTS, servers, allowed) {
        try {
            context.startActivity(openByDefaultIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // no settings app for it
        }
    }
}

@Composable
fun LinksSectionContent(hosts: List<String>, serverHosts: List<String>, allowed: Set<String>?, onOpenSettings: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_links_title)) },
        supportingContent = {
            Column {
                if (hosts.isEmpty()) {
                    Text(stringResource(R.string.settings_links_none))
                    return@Column
                }
                Text(stringResource(R.string.settings_links_hint, hosts.joinToString()))
                allowed?.let { set ->
                    val ok = hosts.filter { it in set }
                    Text(
                        if (ok.isEmpty()) stringResource(R.string.settings_links_not_enabled) else stringResource(R.string.settings_links_enabled, ok.joinToString()),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("linksAllowed"),
                    )
                }
                serverHosts.filter { it !in hosts }.takeIf { serverHosts.isNotEmpty() && serverHosts.none { s -> s in hosts } }?.firstOrNull()?.let {
                    Text(stringResource(R.string.settings_links_other_server, it))
                }
            }
        },
        modifier = Modifier.testTag("linksSection"),
    )
    if (hosts.isNotEmpty()) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).testTag("openByDefault")) {
            Text(stringResource(R.string.settings_links_open))
        }
    }
}
