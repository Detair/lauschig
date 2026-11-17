package io.wolftown.lauschig.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.wolftown.lauschig.ui.nav.Setup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import io.wolftown.lauschig.AppPages
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.links.VoiceRequest
import io.wolftown.lauschig.tv.profiles.TvNoticeDialog
import io.wolftown.lauschig.ui.profiles.profileOutcomeText
import io.wolftown.lauschig.tv.nav.TvNavHost
import io.wolftown.lauschig.tv.nav.openPlayer
import io.wolftown.lauschig.tv.nav.TvProfiles
import io.wolftown.lauschig.tv.nav.tvStart
import io.wolftown.lauschig.ui.AppViewModel
import io.wolftown.lauschig.ui.WindowBackground
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.nav.AccountNavigation
import io.wolftown.lauschig.ui.nav.AccountPages
import io.wolftown.lauschig.ui.nav.AccountPagesScope
import io.wolftown.lauschig.ui.profiles.ProfileOutcome
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.rememberSettledOffline
import androidx.compose.material3.LocalContentColor as Material3ContentColor

/**
 * The TV interface's frame: what the pages read from above (the server for covers, whether to dim what needs it), the theme,
 * and the pages of the account on screen.
 *
 * [openRequest] comes from the media session or the launcher's now-playing card
 * ([io.wolftown.lauschig.MainActivity.EXTRA_OPEN]): "player" opens the player once "who is listening?" is answered (see
 * [TvOpenRequest]); anything else has no page on the TV and is let go.
 */
@Composable
fun TvRoot(
    pages: AppPages,
    openRequest: String?,
    onOpenHandled: () -> Unit,
    voiceRequest: VoiceRequest? = null,
    onVoiceHandled: () -> Unit = {},
    playVoice: (VoiceRequest) -> Unit = {},
    viewModel: AppViewModel = hiltViewModel(),
    pagesScope: AccountPagesScope = hiltViewModel(),
    profiles: ProfilesViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    val rows by profiles.profiles.collectAsStateWithLifecycle()
    val outcome by profiles.outcome.collectAsStateWithLifecycle()
    // A switch that worked shows itself: the next profile's pages are there. Taken off here, as the page that started it is
    // gone by then; a failure stays for "who is listening?" to show, an unlock is said below (its protection is off now).
    LaunchedEffect(outcome) { outcome?.takeIf { it.kind in SHOWN_BY_THEIR_PAGES }?.let(profiles::shown) }
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        TvTheme {
            WindowBackground()
            // Opaque, and the content colour for both kinds of components: text without its own colour would be black.
            Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) {
                CompositionLocalProvider(Material3ContentColor provides MaterialTheme.colorScheme.onBackground) {
                    TvAccountPages(pages, rows?.size, pagesScope) { account, start ->
                        TvNavHost(account, start)
                        TvOpenRequest(account.controller, openRequest, onOpenHandled) {
                            // As on the phone: the service may hold a title this process has not seen yet.
                            viewModel.player.adoptFromService()
                            withTimeoutOrNull(PLAYER_OPEN_WAIT_MS) { viewModel.player.state.first { it.itemId != null } } != null
                        }
                        TvVoiceRequest(account.controller, voiceRequest, onVoiceHandled, playVoice)
                    }
                    TvUnlockedNotice(outcome, profiles::shown)
                }
            }
        }
    }
}

/**
 * An unlock through the sign-in (a forgotten PIN) took the profile's protection off: said once, as the phone does, as that
 * is not what anybody sees on the pages.
 */
@Composable
internal fun TvUnlockedNotice(outcome: ProfileOutcome?, onShown: (ProfileOutcome) -> Unit) {
    outcome?.takeIf { it.kind == ProfileOutcome.Kind.UNLOCKED }?.let { unlocked ->
        TvNoticeDialog(profileOutcomeText(unlocked), onOk = { onShown(unlocked) })
    }
}

/**
 * The pages of the account on screen, and where they start. With more than one profile on the device, the TV asks "who is
 * listening?" first (TV spec E8), once per start of the app: a recreation of the activity does not ask again, a new start
 * does. A switch of the profile there brings the next profile's pages, which start on their start page. Until the profiles
 * have been read ([profileCount] null) nothing shows, as the first page cannot change once it is there.
 */
@Composable
internal fun TvAccountPages(
    pages: AppPages,
    profileCount: Int?,
    scope: AccountPagesScope,
    host: @Composable (account: AccountNavigation, start: Any) -> Unit,
) {
    // Still to be decided for this start of the app; saved, so that a recreation does not ask again.
    var whoIsListeningDue by rememberSaveable { mutableStateOf(true) }
    if (whoIsListeningDue && profileCount == null) return
    AccountPages(pages.owner, scope) { account ->
        // Per account and saved with its pages: their back stack after a recreation was built on this start.
        val askFirst = rememberSaveable { whoIsListeningDue && (profileCount ?: 0) > 1 }
        LaunchedEffect(Unit) { whoIsListeningDue = false }
        host(account, if (askFirst) TvProfiles else tvStart(pages.start))
    }
}

private val SHOWN_BY_THEIR_PAGES = setOf(ProfileOutcome.Kind.SWITCHED, ProfileOutcome.Kind.REMOVED)

/**
 * Carries out [request] on the pages [nav] shows. The player ([MainActivity.OPEN_PLAYER]) waits while "who is listening?" or
 * the sign-in is on screen: it must not jump over the question (TV spec E8), and it opens on top of whatever page comes
 * after it, so Back leads there; opened at the start, that is the start page (review focus 3). It opens only if a title is
 * loaded ([playerLoaded]), as on the phone. A request the TV has no page for is let go.
 */
@Composable
internal fun TvOpenRequest(nav: NavHostController, request: String?, onHandled: () -> Unit, playerLoaded: suspend () -> Boolean) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val handled by rememberUpdatedState(onHandled)
    val loaded by rememberUpdatedState(playerLoaded)
    LaunchedEffect(request, destination) {
        if (request == null || destination == null) return@LaunchedEffect
        if (request != MainActivity.OPEN_PLAYER) return@LaunchedEffect handled()
        if (waitsForAnAnswer(destination)) return@LaunchedEffect
        if (loaded()) nav.openPlayer()
        handled()
    }
}

/** "Who is listening?" or the sign-in is on screen: nothing may jump over them (TV spec E8). */
private fun waitsForAnAnswer(destination: NavDestination): Boolean = destination.hasRoute<TvProfiles>() || destination.hasRoute<Setup>()

/**
 * "Play X in Lauschig" from the Assistant ([MainActivity] hands it on to the TV). Like the player request it waits while "who
 * is listening?" or the sign-in is on screen, so that a voice cannot play a protected profile's titles before its PIN; then
 * [play] runs it once ([io.wolftown.lauschig.auto.VoicePlayer], which also opens the player once something plays).
 */
@Composable
internal fun TvVoiceRequest(nav: NavHostController, request: VoiceRequest?, onHandled: () -> Unit, play: (VoiceRequest) -> Unit) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val handled by rememberUpdatedState(onHandled)
    val run by rememberUpdatedState(play)
    LaunchedEffect(request, destination) {
        if (request == null || destination == null || waitsForAnAnswer(destination)) return@LaunchedEffect
        // Taken off first: whatever comes of it, it runs once.
        handled()
        run(request)
    }
}

private const val PLAYER_OPEN_WAIT_MS = 2_000L
