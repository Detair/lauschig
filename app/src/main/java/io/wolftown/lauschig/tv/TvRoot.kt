package io.wolftown.lauschig.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.tv.nav.TvNavHost
import io.wolftown.lauschig.tv.nav.TvProfiles
import io.wolftown.lauschig.tv.nav.tvStart
import io.wolftown.lauschig.ui.AppViewModel
import io.wolftown.lauschig.ui.WindowBackground
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.nav.AccountNavigation
import io.wolftown.lauschig.ui.nav.AccountPages
import io.wolftown.lauschig.ui.nav.AccountPagesScope
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.rememberSettledOffline
import androidx.compose.material3.LocalContentColor as Material3ContentColor

/**
 * The TV interface's frame: what the pages read from above (the server for covers, whether to dim what needs it), the theme,
 * and the pages of the account on screen.
 *
 * [openRequest] comes from a notification or the media session ([io.wolftown.lauschig.MainActivity.EXTRA_OPEN]). The TV has no
 * page for it yet, so it is let go at once instead of waiting to fire later.
 */
@Composable
fun TvRoot(
    pages: AppPages,
    openRequest: String?,
    onOpenHandled: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
    pagesScope: AccountPagesScope = hiltViewModel(),
    profiles: ProfilesViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    val rows by profiles.profiles.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) { if (openRequest != null) onOpenHandled() }
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        TvTheme {
            WindowBackground()
            // Opaque, and the content colour for both kinds of components: text without its own colour would be black.
            Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) {
                CompositionLocalProvider(Material3ContentColor provides MaterialTheme.colorScheme.onBackground) {
                    TvAccountPages(pages, rows?.size, pagesScope) { account, start -> TvNavHost(account, start) }
                }
            }
        }
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
