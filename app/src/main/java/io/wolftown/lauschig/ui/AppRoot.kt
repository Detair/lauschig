package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import io.wolftown.lauschig.AppPages
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.links.LinkTarget
import io.wolftown.lauschig.links.LinkViewModel
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.ui.adaptive.rememberNavigationRail
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.PositionJumpSnackbar
import io.wolftown.lauschig.ui.nav.AccountPages
import io.wolftown.lauschig.ui.nav.AccountPagesScope
import io.wolftown.lauschig.ui.nav.AppNavHost
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.Cleanup
import io.wolftown.lauschig.ui.nav.ConnectionStatus
import io.wolftown.lauschig.ui.nav.ItemDetail
import io.wolftown.lauschig.ui.nav.Player
import io.wolftown.lauschig.ui.nav.PlayerOverlay
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.nav.closePlayer
import io.wolftown.lauschig.ui.nav.currentTab
import io.wolftown.lauschig.ui.nav.isOnSetup
import io.wolftown.lauschig.ui.nav.isOnYearReview
import io.wolftown.lauschig.ui.nav.navigateToTab
import io.wolftown.lauschig.ui.nav.openDetailsFromPlayer
import io.wolftown.lauschig.ui.nav.rememberPlayerLayer
import io.wolftown.lauschig.ui.nav.route
import io.wolftown.lauschig.ui.nav.underPlayer
import io.wolftown.lauschig.ui.player.MiniPlayer
import io.wolftown.lauschig.ui.player.PlayerRoute
import io.wolftown.lauschig.ui.player.PlayerViewModel
import io.wolftown.lauschig.ui.profiles.ProfileBusyDialog
import io.wolftown.lauschig.ui.profiles.ProfileHeaderButton
import io.wolftown.lauschig.ui.profiles.ProfileRemoveDialog
import io.wolftown.lauschig.ui.profiles.ProfilesSheet
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.profiles.profileOutcomeText
import io.wolftown.lauschig.ui.setup.SsoReturnViewModel
import io.wolftown.lauschig.whatsnew.WhatsNewSheet
import io.wolftown.lauschig.whatsnew.WhatsNewViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    /** Whose pages are on screen and where they start; new pages with every other account. */
    pages: AppPages,
    appearance: Appearance = Appearance(),
    /** The visible tabs in their order. */
    tabs: List<TopLevelTab> = TopLevelTab.entries,
    /** The chosen start screen (where the first sign-in leads). */
    startTab: TopLevelTab = TopLevelTab.Start,
    viewModel: AppViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
    linkRequest: String? = null,
    onLinkHandled: () -> Unit = {},
    links: LinkViewModel = hiltViewModel(),
    whatsNew: WhatsNewViewModel = hiltViewModel(),
    previousAccount: PreviousAccountViewModel = hiltViewModel(),
    finishedQuestions: io.wolftown.lauschig.cleanup.FinishedQuestionViewModel = hiltViewModel(),
    pagesScope: AccountPagesScope = hiltViewModel(),
    profiles: ProfilesViewModel = hiltViewModel(),
    ssoReturn: SsoReturnViewModel = hiltViewModel(),
    /** An account is signed in (null: not read yet); false while running = signed out, only the sign-in is left. */
    signedIn: Boolean? = null,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val serverId by viewModel.serverId.collectAsStateWithLifecycle()
    val needsReauth by viewModel.needsReauth.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    // The same (activity-wide) view model as the player layer's: its state knows the loaded title's cover version.
    val playerScreen by playerViewModel.state.collectAsStateWithLifecycle()
    val downloadPrompt by viewModel.downloadPrompt.collectAsStateWithLifecycle()
    val profileRows by profiles.profiles.collectAsStateWithLifecycle()

    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    // One banner state for the frame and the player layer, so both show the same hint at the same time.
    val connectionBanner = rememberConnectionBanner(connection, checking)
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        LauschigTheme(appearance) {
            WindowBackground()
            AccountPages(pages.owner, pagesScope) { account ->
                val navController = account.controller
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

                // Back from the browser with no form there to take it (a process death and no saved page): one opens for it.
                // The attempt itself knows what it was started as.
                val ssoWaiting by ssoReturn.waiting.collectAsStateWithLifecycle()
                LaunchedEffect(ssoWaiting) {
                    val mode = ssoWaiting ?: return@LaunchedEffect
                    if (navController.currentDestination?.hasRoute<Setup>() != true) navController.navigate(Setup(mode)) { launchSingleTop = true }
                }

                val onSetup = navController.isOnSetup()
                val onReview = navController.isOnYearReview()
                // The player is a layer above the frame (PlayerOverlay); the frame drops its bars only while the player covers it.
                val playerLayer = rememberPlayerLayer(navController)
                val playerCovers = playerLayer.coversFrame
                val playerUp = playerLayer.up
                val showReauth = needsReauth && !onSetup
                val onReauth = { navController.navigate(Setup()) { launchSingleTop = true } }
                val onOpenConnection = { navController.navigate(ConnectionStatus) { launchSingleTop = true } }
                var profilesOpen by rememberSaveable { mutableStateOf(false) }
                AppScaffold(
                    connection = connection,
                    // Under the player only: unseen, not for TalkBack, and no keyboard focus moves into it.
                    modifier = Modifier.underPlayer(playerCovers),
                    miniPlayer = {
                        if (!onSetup && !playerCovers && !onReview) {
                            MiniPlayer(
                                playerState,
                                onToggle = viewModel.player::togglePlayPause,
                                onOpen = { navController.navigate(Player) { launchSingleTop = true } },
                                coverVersion = playerScreen.coverVersion.takeIf { playerScreen.itemId == playerState.itemId },
                            )
                        }
                    },
                    // The full player covers the whole window (its buttons stay above the fold); it has its own close.
                    // The year in review covers the frame too (a story over the whole window).
                    showBottomBar = !onSetup && !playerCovers && !onReview,
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
                    AppNavHost(
                        account, pages.start, homeRoute = startTab.route(), snackbarHost = snackbarHost,
                        onOpenProfiles = { profilesOpen = true },
                        startHeader = { ProfileHeaderButton(profileRows, onClick = { profilesOpen = true }) },
                    )
                }
                PlayerOverlay(
                    playerLayer.slide,
                    banner = connectionBanner.takeIf { !onSetup },
                    showReauthBanner = showReauth,
                    onReauth = onReauth,
                    onOpenConnection = onOpenConnection,
                    snackbarHostState = snackbarHost,
                    onClose = navController::closePlayer,
                ) {
                    PlayerRoute(onClose = navController::closePlayer, onOpenDetails = navController::openDetailsFromPlayer)
                }
                // Once after an update (never on a first install or over the sign-in).
                val news by whatsNew.pending.collectAsStateWithLifecycle()
                if (news.isNotEmpty() && !onSetup) WhatsNewSheet(news, whatsNew::dismiss)
                // Another account kept its downloads here: asked once after the sign-in.
                val previous by previousAccount.question.collectAsStateWithLifecycle()
                val deletingPrevious by previousAccount.deleting.collectAsStateWithLifecycle()
                val previousFailed by previousAccount.deleteFailed.collectAsStateWithLifecycle()
                previous?.let { if (!onSetup) PreviousAccountDialog(it, deletingPrevious, previousAccount::delete, previousAccount::keep, previousFailed) }
                if (profilesOpen) {
                    ProfilesSheet(
                        profileRows.orEmpty(),
                        onSwitch = {
                            profilesOpen = false
                            profiles.switchTo(it)
                        },
                        onRemove = profiles::askRemove,
                        onAdd = {
                            profilesOpen = false
                            navController.navigate(Setup(SetupMode.ADD_PROFILE)) { launchSingleTop = true }
                        },
                        onDismiss = { profilesOpen = false },
                    )
                }
                val removal by profiles.removal.collectAsStateWithLifecycle()
                removal?.let {
                    ProfileRemoveDialog(
                        it,
                        onConfirm = {
                            profilesOpen = false // the result comes as a snackbar
                            profiles.confirmRemove()
                        },
                        onCancel = profiles::cancelRemove,
                    )
                }
            }
            // Outside the pages: it stays up while they change underneath, and a recreated activity finds it again.
            val profileWork by profiles.busy.collectAsStateWithLifecycle()
            profileWork?.let { ProfileBusyDialog(it) }
            val profileOutcome by profiles.outcome.collectAsStateWithLifecycle()
            profileOutcome?.let { outcome ->
                val text = profileOutcomeText(outcome)
                LaunchedEffect(outcome) {
                    // Done only once it was on screen: a rotation in between shows it again rather than losing it.
                    snackbarHost.showSnackbar(text)
                    profiles.shown(outcome)
                }
            }
            PositionJumpSnackbar(viewModel.jumpBus, viewModel.player, serverId, snackbarHost)
            downloadPrompt?.let {
                DownloadPromptDialog(it, viewModel::answerMetered, viewModel::answerContention, viewModel::dismissPrompt)
            }
            // "Download löschen?" after "Als gehört markieren": here it outlives the menu that asked and a rotation.
            val finishedQuestion by finishedQuestions.question.collectAsStateWithLifecycle()
            val autoDeleting by finishedQuestions.autoDeleting.collectAsStateWithLifecycle()
            finishedQuestion.firstOrNull()?.let { q ->
                io.wolftown.lauschig.ui.common.FinishedQuestionDialog(
                    autoDeleting, finishedQuestions.titleOf(q), onDelete = { finishedQuestions.delete(q) }, onKeep = { finishedQuestions.keep(q) },
                )
            }
        }
    }
}
