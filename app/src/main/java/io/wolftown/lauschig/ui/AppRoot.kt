package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.ui.nav.Cleanup
import io.wolftown.lauschig.ui.nav.ConnectionStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.CompositionLocalProvider
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.PositionJumpSnackbar
import io.wolftown.lauschig.ui.nav.AppNavHost
import io.wolftown.lauschig.ui.nav.currentTab
import io.wolftown.lauschig.ui.nav.Player
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.isOnPlayer
import io.wolftown.lauschig.ui.nav.isOnSetup
import io.wolftown.lauschig.ui.player.MiniPlayer
import io.wolftown.lauschig.ui.nav.navigateToTab
import io.wolftown.lauschig.links.LinkTarget
import io.wolftown.lauschig.links.LinkViewModel
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.ItemDetail
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long a widget tap waits for the service's title before it stays on the start screen. */
private const val PLAYER_OPEN_WAIT_MS = 2_000L

/** Wires ViewModel state, navigation and the shell together; hosts the mini player above the bottom bar. */
@Composable
fun AppRoot(
    startDestination: Any,
    viewModel: AppViewModel = hiltViewModel(),
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
    linkRequest: String? = null,
    onLinkHandled: () -> Unit = {},
    links: LinkViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(openRequest) {
        when (openRequest) {
            MainActivity.OPEN_CLEANUP -> {
                navController.navigate(Cleanup) { launchSingleTop = true }
                onOpenHandled()
            }
            MainActivity.OPEN_PLAYER -> {
                // From the widget: the service may hold a title the app has not seen yet (it was started from the widget).
                viewModel.player.adoptFromService()
                val loaded = withTimeoutOrNull(PLAYER_OPEN_WAIT_MS) { viewModel.player.state.first { it.itemId != null } } != null
                if (loaded && navController.currentDestination?.hasRoute<Setup>() != true) navController.navigate(Player) { launchSingleTop = true }
                onOpenHandled()
            }
        }
    }
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val serverId by viewModel.serverId.collectAsStateWithLifecycle()
    val needsReauth by viewModel.needsReauth.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(linkRequest) {
        val url = linkRequest ?: return@LaunchedEffect
        when (val target = links.open(url)) {
            is LinkTarget.Item -> navController.navigate(ItemDetail(target.itemId)) { launchSingleTop = true }
            is LinkTarget.Series -> navController.navigate(Browse(BrowseKind.SERIES, target.name)) { launchSingleTop = true }
            // Outside this effect: clearing the request restarts it, which would dismiss the snackbar at once.
            is LinkTarget.Message -> scope.launch { snackbarHost.showSnackbar(context.getString(target.text)) }
        }
        onLinkHandled()
    }

    val onSetup = navController.isOnSetup()
    val onPlayer = navController.isOnPlayer()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val downloadPrompt by viewModel.downloadPrompt.collectAsStateWithLifecycle()

    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        LauschigTheme {
            AppScaffold(
                connection = connection,
                miniPlayer = {
                    if (!onSetup && !onPlayer) {
                        MiniPlayer(
                            playerState,
                            onToggle = viewModel.player::togglePlayPause,
                            onOpen = { navController.navigate(Player) { launchSingleTop = true } },
                        )
                    }
                },
                showBottomBar = !onSetup,
                showOfflineBanner = !onSetup,
                showReauthBanner = needsReauth && !onSetup,
                onReauth = { navController.navigate(Setup) { launchSingleTop = true } },
                connectionChecking = checking,
                onOpenConnection = { navController.navigate(ConnectionStatus) { launchSingleTop = true } },
                selectedTab = navController.currentTab(),
                onTabSelected = navController::navigateToTab,
                snackbarHostState = snackbarHost,
            ) {
                AppNavHost(navController, startDestination)
            }
            PositionJumpSnackbar(viewModel.jumpBus, viewModel.player, serverId, snackbarHost)
            downloadPrompt?.let {
                DownloadPromptDialog(it, viewModel::answerMetered, viewModel::answerContention, viewModel::dismissPrompt)
            }
        }
    }
}
