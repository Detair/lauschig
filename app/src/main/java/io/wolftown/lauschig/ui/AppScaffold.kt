package io.wolftown.lauschig.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.delay

/** The four bottom-navigation tabs. */
enum class TopLevelTab(@StringRes val label: Int, val icon: ImageVector) {
    Start(R.string.nav_start, Icons.Rounded.Home),
    Library(R.string.nav_library, Icons.Rounded.LibraryBooks),
    Discover(R.string.nav_discover, Icons.Rounded.Explore),
    Search(R.string.nav_search, Icons.Rounded.Search),
}

/**
 * Offline must persist this long before the banner appears: the shared connection flow starts as
 * [ConnectionState.Offline] until the first probe resolves, which must not flash "offline" at cold start.
 * Going online hides it immediately.
 */
const val OFFLINE_BANNER_DELAY_MS = 2_000L

/**
 * App frame: offline banner on top, [content] in the middle, [miniPlayer] slot above the bottom bar.
 * [content] receives zero padding; this scaffold already applies the system-bar insets around it.
 */
@Composable
fun AppScaffold(
    connection: ConnectionState,
    miniPlayer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: TopLevelTab? = TopLevelTab.Start,
    showBottomBar: Boolean = true,
    onTabSelected: (TopLevelTab) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val isOffline = connection is ConnectionState.Offline
    var delayElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(isOffline) {
        delayElapsed = false
        if (isOffline) {
            delay(OFFLINE_BANNER_DELAY_MS)
            delayElapsed = true
        }
    }
    // Derived, so going online hides the banner in the same composition (no effect round trip).
    val showBanner = isOffline && delayElapsed

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) Column {
                miniPlayer()
                NavigationBar {
                    TopLevelTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { onTabSelected(tab) },
                            icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.testTag("navIcon")) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (showBanner) OfflineBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues()) }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("offlineBanner"),
    ) {
        Text(
            stringResource(R.string.offline_banner),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
