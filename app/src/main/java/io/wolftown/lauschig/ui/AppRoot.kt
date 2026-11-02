package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.ui.nav.Cleanup
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

/** Wires ViewModel state, navigation and the shell together; hosts the mini player above the bottom bar. */
@Composable
fun AppRoot(
    startDestination: Any,
    viewModel: AppViewModel = hiltViewModel(),
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    LaunchedEffect(openRequest) {
        if (openRequest == MainActivity.OPEN_CLEANUP) {
            navController.navigate(Cleanup) { launchSingleTop = true }
            onOpenHandled()
        }
    }
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val serverId by viewModel.serverId.collectAsStateWithLifecycle()
    val needsReauth by viewModel.needsReauth.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val onSetup = navController.isOnSetup()
    val onPlayer = navController.isOnPlayer()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val downloadPrompt by viewModel.downloadPrompt.collectAsStateWithLifecycle()

    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection)
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
