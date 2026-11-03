package io.wolftown.lauschig.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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

/** The four top-level tabs; their order, visibility and the start screen are the user's (`TabLayout`). */
enum class TopLevelTab(@StringRes val label: Int, val icon: ImageVector) {
    Start(R.string.nav_start, Icons.Rounded.Home),
    Library(R.string.nav_library, Icons.Rounded.LibraryBooks),
    Discover(R.string.nav_discover, Icons.Rounded.Explore),
    Search(R.string.nav_search, Icons.Rounded.Search),
}

/**
 * Not-online must persist this long before a banner appears: the shared connection flow starts as
 * [ConnectionState.Offline] until the first probe resolves (and falls back to it when the app was in the background
 * for a while), which must not flash "offline". Going online hides it immediately.
 */
const val OFFLINE_BANNER_DELAY_MS = 3_000L

/** The connection hint on top of the app. */
enum class ConnectionBanner { OFFLINE, CONNECTING }

/**
 * The banner for [connection] once the app has not been online for [OFFLINE_BANNER_DELAY_MS]: [ConnectionBanner.OFFLINE]
 * only when a probe answered "offline", [ConnectionBanner.CONNECTING] while the first check since the start is still
 * running ([checking], see `ApiProvider.checking`); null when online (at once).
 */
@Composable
fun rememberConnectionBanner(connection: ConnectionState, checking: Boolean): ConnectionBanner? {
    val target = when {
        connection is ConnectionState.Online -> null
        checking -> ConnectionBanner.CONNECTING
        else -> ConnectionBanner.OFFLINE
    }
    val notOnline = target != null
    var delayElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(notOnline) {
        delayElapsed = false
        if (notOnline) {
            delay(OFFLINE_BANNER_DELAY_MS)
            delayElapsed = true
        }
    }
    return if (notOnline && delayElapsed) target else null
}

/**
 * True once [connection] has been answered [ConnectionState.Offline] and the app was not online for
 * [OFFLINE_BANNER_DELAY_MS]; false immediately when online and while the first check runs ([checking]). Shared by the
 * banner and the tile dimming so both settle identically.
 */
@Composable
fun rememberSettledOffline(connection: ConnectionState, checking: Boolean = false): Boolean =
    rememberConnectionBanner(connection, checking) == ConnectionBanner.OFFLINE

/**
 * App frame: re-auth hint and offline banner on top, [content] in the middle, [miniPlayer] slot above the bottom bar.
 * [content] receives zero padding; this scaffold already applies the system-bar insets around it.
 */
@Composable
fun AppScaffold(
    connection: ConnectionState,
    miniPlayer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: TopLevelTab? = TopLevelTab.Start,
    /** The visible tabs in their order (see `TabLayout`). */
    tabs: List<TopLevelTab> = TopLevelTab.entries,
    /** Whether the tabs are shown at all (bottom bar or rail). */
    showBottomBar: Boolean = true,
    /** Medium/expanded widths: the tabs in a navigation rail on the start side, the mini player at the bottom of the content. */
    navigationRail: Boolean = false,
    showOfflineBanner: Boolean = true,
    showReauthBanner: Boolean = false,
    onReauth: () -> Unit = {},
    /** The first connection check since the start is still running (see [rememberConnectionBanner]). */
    connectionChecking: Boolean = false,
    /** Tapping the offline/connecting banner opens the Verbindungsstatus window. */
    onOpenConnection: () -> Unit = {},
    onTabSelected: (TopLevelTab) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    // Derived, so going online hides the banner in the same composition (no effect round trip).
    val banner = rememberConnectionBanner(connection, connectionChecking).takeIf { showOfflineBanner }

    val body: @Composable (PaddingValues) -> Unit = { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (showReauthBanner) ReauthBanner(onReauth)
            banner?.let { ConnectionHint(it, onOpenConnection) }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues()) }
        }
    }
    if (navigationRail) {
        // System bars and a display cutout (phones in landscape), not the keyboard: screens with a text field handle it.
        val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        Row(modifier.fillMaxSize()) {
            if (showBottomBar) {
                NavigationRail(Modifier.testTag("navRail"), windowInsets = safe.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                    // Centred on the side, easy to reach with a thumb holding a tablet.
                    Spacer(Modifier.weight(1f))
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = tab == selectedTab,
                            onClick = { onTabSelected(tab) },
                            icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.testTag("navIcon")) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            val sides = if (showBottomBar) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = safe.only(sides),
                // Without a bottom bar the mini player keeps clear of the navigation bar itself.
                bottomBar = { Column(Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))) { miniPlayer() } },
                content = body,
            )
        }
        return
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) Column {
                miniPlayer()
                NavigationBar {
                    tabs.forEach { tab ->
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
        content = body,
    )
}

@Composable
private fun ConnectionHint(banner: ConnectionBanner, onClick: () -> Unit) {
    val offline = banner == ConnectionBanner.OFFLINE
    Surface(
        color = if (offline) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (offline) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(if (offline) "offlineBanner" else "connectingBanner"),
    ) {
        Text(
            stringResource(if (offline) R.string.offline_banner else R.string.connecting_banner),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** Persistent hint after a refresh failed with 401: local data and playback keep working, sign-in is offered. */
@Composable
private fun ReauthBanner(onReauth: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("reauthBanner"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                stringResource(R.string.reauth_banner),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            TextButton(onClick = onReauth) { Text(stringResource(R.string.reauth_action)) }
        }
    }
}
