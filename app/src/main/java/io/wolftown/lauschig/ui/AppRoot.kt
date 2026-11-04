package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.ui.adaptive.rememberNavigationRail
import io.wolftown.lauschig.whatsnew.WhatsNewSheet
import io.wolftown.lauschig.whatsnew.WhatsNewViewModel
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
import io.wolftown.lauschig.ui.nav.route
import io.wolftown.lauschig.ui.nav.currentTab
import io.wolftown.lauschig.ui.nav.Player
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.isOnPlayer
import io.wolftown.lauschig.ui.nav.PlayerOverlay
import io.wolftown.lauschig.ui.nav.closePlayer
import io.wolftown.lauschig.ui.nav.openDetailsFromPlayer
import io.wolftown.lauschig.ui.nav.playerCoversFrame
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.wolftown.lauschig.ui.nav.isOnSetup
import io.wolftown.lauschig.ui.player.MiniPlayer
import io.wolftown.lauschig.ui.nav.navigateToTab
import io.wolftown.lauschig.ui.nav.showSignIn
import io.wolftown.lauschig.links.LinkTarget
import io.wolftown.lauschig.links.LinkViewModel
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.ItemDetail
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What to do with a request from outside (a notification, a widget tap, a link) right now. */
enum class IncomingGate { WAIT, DROP, OPEN }

/**
 * Not read yet: wait. Signed out: drop it – no page may open over the sign-in (the screens of the old account's
 * notification or link would be empty anyway). Signed in: open it. The SSO callback is not such a request.
 */
fun incomingGate(signedIn: Boolean?): IncomingGate = when (signedIn) {
    null -> IncomingGate.WAIT
    false -> IncomingGate.DROP
    true -> IncomingGate.OPEN
}

/** How long a widget tap waits for the service's title before it stays on the start screen. */
private const val PLAYER_OPEN_WAIT_MS = 2_000L

/** Wires ViewModel state, navigation and the shell together; hosts the mini player above the bottom bar. */
@Composable
fun AppRoot(
    startDestination: Any,
    appearance: Appearance = Appearance(),
    /** The visible tabs in their order. */
    tabs: List<TopLevelTab> = TopLevelTab.entries,
    /** The chosen start screen (where the first sign-in leads). */
    startTab: TopLevelTab = TopLevelTab.Start,
    viewModel: AppViewModel = hiltViewModel(),
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
    linkRequest: String? = null,
    onLinkHandled: () -> Unit = {},
    links: LinkViewModel = hiltViewModel(),
    whatsNew: WhatsNewViewModel = hiltViewModel(),
    previousAccount: PreviousAccountViewModel = hiltViewModel(),
    /** An account is signed in (null: not read yet); false while running = signed out, only the sign-in is left. */
    signedIn: Boolean? = null,
) {
    val navController = rememberNavController()
    LaunchedEffect(signedIn) {
        if (signedIn == false) navController.showSignIn()
    }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(openRequest, signedIn) {
        if (openRequest == null) return@LaunchedEffect
        when (incomingGate(signedIn)) {
            IncomingGate.WAIT -> return@LaunchedEffect
            IncomingGate.DROP -> { onOpenHandled(); return@LaunchedEffect }
            IncomingGate.OPEN -> Unit
        }
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
    LaunchedEffect(linkRequest, signedIn) {
        val url = linkRequest ?: return@LaunchedEffect
        when (incomingGate(signedIn)) {
            IncomingGate.WAIT -> return@LaunchedEffect
            IncomingGate.DROP -> { onLinkHandled(); return@LaunchedEffect }
            IncomingGate.OPEN -> Unit
        }
        when (val target = links.open(url)) {
            is LinkTarget.Item -> navController.navigate(ItemDetail(target.itemId)) { launchSingleTop = true }
            is LinkTarget.Series -> navController.navigate(Browse(BrowseKind.SERIES, target.name)) { launchSingleTop = true }
            // Outside this effect: clearing the request restarts it, which would dismiss the snackbar at once.
            is LinkTarget.Message -> scope.launch { snackbarHost.showSnackbar(resources.getString(target.text)) }
        }
        onLinkHandled()
    }

    val onSetup = navController.isOnSetup()
    val onPlayer = navController.isOnPlayer()
    // The player is a layer above the frame (PlayerOverlay); the frame drops its bars only while the player covers it.
    val playerSlide = remember { MutableTransitionState(false) }
    if (playerSlide.targetState != onPlayer) playerSlide.targetState = onPlayer
    val playerCovers = playerCoversFrame(onPlayer, playerSlide)
    val playerUp = onPlayer || playerSlide.currentState || playerSlide.targetState
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val downloadPrompt by viewModel.downloadPrompt.collectAsStateWithLifecycle()

    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    // One banner state for the frame and the player layer, so both show the same hint at the same time.
    val connectionBanner = rememberConnectionBanner(connection, checking)
    val showReauth = needsReauth && !onSetup
    val onReauth = { navController.navigate(Setup) { launchSingleTop = true } }
    val onOpenConnection = { navController.navigate(ConnectionStatus) { launchSingleTop = true } }
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        LauschigTheme(appearance) {
            WindowBackground()
            AppScaffold(
                connection = connection,
                // Under the player only: unseen, and not for TalkBack either.
                modifier = if (playerCovers) Modifier.clearAndSetSemantics {} else Modifier,
                miniPlayer = {
                    if (!onSetup && !playerCovers) {
                        MiniPlayer(
                            playerState,
                            onToggle = viewModel.player::togglePlayPause,
                            onOpen = { navController.navigate(Player) { launchSingleTop = true } },
                        )
                    }
                },
                // The full player covers the whole window (its buttons stay above the fold); it has its own close.
                showBottomBar = !onSetup && !playerCovers,
                navigationRail = rememberNavigationRail(),
                showOfflineBanner = !onSetup,
                showReauthBanner = showReauth,
                onReauth = onReauth,
                connectionChecking = checking,
                onOpenConnection = onOpenConnection,
                selectedTab = navController.currentTab(),
                tabs = tabs,
                onTabSelected = navController::navigateToTab,
                snackbarHostState = snackbarHost,
                showSnackbars = !playerUp,
                connectionBanner = connectionBanner,
            ) {
                AppNavHost(navController, startDestination, homeRoute = startTab.route())
            }
            PlayerOverlay(
                playerSlide,
                banner = connectionBanner.takeIf { !onSetup },
                showReauthBanner = showReauth,
                onReauth = onReauth,
                onOpenConnection = onOpenConnection,
                snackbarHostState = snackbarHost,
                onClose = navController::closePlayer,
                onOpenDetails = navController::openDetailsFromPlayer,
            )
            PositionJumpSnackbar(viewModel.jumpBus, viewModel.player, serverId, snackbarHost)
            downloadPrompt?.let {
                DownloadPromptDialog(it, viewModel::answerMetered, viewModel::answerContention, viewModel::dismissPrompt)
            }
            // Once after an update (never on a first install or over the sign-in).
            val news by whatsNew.pending.collectAsStateWithLifecycle()
            if (news.isNotEmpty() && !onSetup) WhatsNewSheet(news, whatsNew::dismiss)
            // Another account kept its downloads here: asked once after the sign-in.
            val previous by previousAccount.question.collectAsStateWithLifecycle()
            val deletingPrevious by previousAccount.deleting.collectAsStateWithLifecycle()
            val previousFailed by previousAccount.deleteFailed.collectAsStateWithLifecycle()
            previous?.let { if (!onSetup) PreviousAccountDialog(it, deletingPrevious, previousAccount::delete, previousAccount::keep, previousFailed) }
        }
    }
}
