package io.wolftown.lauschig.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.wolftown.lauschig.tv.browse.TvBrowseRoute
import io.wolftown.lauschig.tv.browse.TvItemRoute
import io.wolftown.lauschig.tv.browse.TvLibraryRoute
import io.wolftown.lauschig.tv.browse.TvSearchRoute
import io.wolftown.lauschig.tv.home.TvHomeRoute
import io.wolftown.lauschig.tv.player.TvPlayerRoute
import io.wolftown.lauschig.tv.profiles.TvProfilesRoute
import io.wolftown.lauschig.tv.settings.TvSettingsRoute
import io.wolftown.lauschig.tv.setup.TvSetupRoute
import io.wolftown.lauschig.ui.nav.AccountNavHost
import io.wolftown.lauschig.ui.nav.AccountNavigation
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.ItemDetail
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.nav.finishSetup

/**
 * The TV's pages of one account. Like the phone's, they live in [AccountNavHost], so a switch of the account ends them all.
 * Back pops a page; on the first page it leaves the app.
 */
@Composable
fun TvNavHost(pages: AccountNavigation, startDestination: Any, modifier: Modifier = Modifier) {
    val navController = pages.controller
    val openSection = { section: TvSection -> navController.openSection(section) }
    AccountNavHost(pages, startDestination, modifier) {
        composable<TvHome> {
            TvShell(TvSection.Home, openSection) {
                TvHomeRoute(
                    onOpenItem = { navController.navigate(ItemDetail(it)) },
                    onOpenSeries = { navController.navigate(Browse(BrowseKind.SERIES, it)) },
                )
            }
        }
        composable<TvLibrary> {
            TvShell(TvSection.Library, openSection) {
                TvLibraryRoute(
                    onOpenItem = { navController.navigate(ItemDetail(it)) },
                    onOpenSeries = { navController.navigate(Browse(BrowseKind.SERIES, it)) },
                )
            }
        }
        composable<TvSearch> {
            TvShell(TvSection.Search, openSection) {
                TvSearchRoute(
                    onOpenItem = { navController.navigate(ItemDetail(it)) },
                    onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
                )
            }
        }
        composable<TvSettings> {
            TvShell(TvSection.Settings, openSection) {
                TvSettingsRoute(
                    onSwitchProfile = { navController.navigate(TvProfiles) { launchSingleTop = true } },
                    onSignIn = { navController.navigate(Setup.unlock(it)) { launchSingleTop = true } },
                )
            }
        }
        // Full screen, without the rail: the remote's keys belong to the player there.
        composable<TvPlayer> { TvPlayerRoute(onBack = { navController.popBackStack() }) }
        // The phone's route classes: their view models read them from their SavedStateHandle.
        composable<ItemDetail> {
            TvItemRoute(
                onPlay = { navController.openPlayer() },
                onOpenSeries = { navController.navigate(Browse(BrowseKind.SERIES, it)) },
            )
        }
        composable<Browse> { TvBrowseRoute(onOpenItem = { navController.navigate(ItemDetail(it)) }) }
        composable<Setup> { TvSetupRoute(onDone = { navController.finishSetup(TvHome) }) }
        composable<TvProfiles> {
            TvProfilesRoute(
                onChosen = { navController.leaveProfiles() },
                onAdd = { navController.navigate(Setup(SetupMode.ADD_PROFILE)) { launchSingleTop = true } },
                onSignIn = { navController.navigate(Setup.unlock(it)) { launchSingleTop = true } },
            )
        }
    }
}

/**
 * From "who is listening?" to the start page. Asked at the start it is the first page and gives way to the start page; opened
 * from the settings, the start page is below it, and the way goes back there instead of stacking a second one.
 */
internal fun NavHostController.leaveProfiles() {
    if (!popBackStack<TvHome>(inclusive = false)) navigate(TvHome) { popUpTo<TvProfiles> { inclusive = true } }
}

/**
 * To a section of the rail. Each one stands right on the start page, so Back from any of them leads there, and from there out
 * of the app.
 */
internal fun NavHostController.openSection(section: TvSection) {
    if (section == TvSection.Home) {
        if (!popBackStack<TvHome>(inclusive = false)) navigate(TvHome) { launchSingleTop = true }
        return
    }
    val route: Any = when (section) {
        TvSection.Library -> TvLibrary
        TvSection.Search -> TvSearch
        TvSection.Settings -> TvSettings
        TvSection.Home -> TvHome
    }
    navigate(route) {
        popUpTo<TvHome>()
        launchSingleTop = true
    }
}

/** To the player; a second call while it is on top adds no second one. */
internal fun NavHostController.openPlayer() = navigate(TvPlayer) { launchSingleTop = true }
