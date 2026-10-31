package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.common.PositionJumpSnackbar
import io.wolftown.lauschig.ui.nav.AppNavHost
import io.wolftown.lauschig.ui.nav.currentTab
import io.wolftown.lauschig.ui.nav.navigateToTab

/** Wires ViewModel state, navigation and the shell together. [miniPlayer] is built in a later task. */
@Composable
fun AppRoot(miniPlayer: @Composable () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val serverId by viewModel.serverId.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LauschigTheme {
        AppScaffold(
            connection = connection,
            miniPlayer = miniPlayer,
            selectedTab = navController.currentTab(),
            onTabSelected = navController::navigateToTab,
            snackbarHostState = snackbarHost,
        ) {
            AppNavHost(navController)
        }
        PositionJumpSnackbar(viewModel.jumpBus, viewModel.player, serverId, snackbarHost)
    }
}
