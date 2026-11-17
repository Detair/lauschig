package io.wolftown.lauschig.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import io.wolftown.lauschig.AppPages
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.tv.nav.TvNavHost
import io.wolftown.lauschig.tv.nav.tvStart
import io.wolftown.lauschig.ui.AppViewModel
import io.wolftown.lauschig.ui.WindowBackground
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.nav.AccountPages
import io.wolftown.lauschig.ui.nav.AccountPagesScope
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
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val baseUrl = (connection as? ConnectionState.Online)?.baseUrl
    val dimOffline = rememberSettledOffline(connection, checking)
    LaunchedEffect(openRequest) { if (openRequest != null) onOpenHandled() }
    CompositionLocalProvider(LocalServerBaseUrl provides baseUrl, LocalOfflineDimming provides dimOffline) {
        TvTheme {
            WindowBackground()
            // Opaque, and the content colour for both kinds of components: text without its own colour would be black.
            Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) {
                CompositionLocalProvider(Material3ContentColor provides MaterialTheme.colorScheme.onBackground) {
                    AccountPages(pages.owner, pagesScope) { account -> TvNavHost(account, tvStart(pages.start)) }
                }
            }
        }
    }
}
