package io.wolftown.lauschig.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.tv.home.TvHomeRoute
import io.wolftown.lauschig.tv.profiles.TvProfilesRoute
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
        // Until their pages come (Task 3 and 5).
        composable<TvLibrary> { TvShell(TvSection.Library, openSection) { Placeholder() } }
        composable<TvSearch> { TvShell(TvSection.Search, openSection) { Placeholder() } }
        composable<TvSettings> { TvShell(TvSection.Settings, openSection) { Placeholder() } }
        // The phone's route classes: their view models read them from their SavedStateHandle.
        composable<ItemDetail> { Placeholder() }
        composable<Browse> { Placeholder() }
        composable<Setup> { TvSetupRoute(onDone = { navController.finishSetup(TvHome) }) }
        composable<TvProfiles> {
            TvProfilesRoute(
                onChosen = { navController.navigate(TvHome) { popUpTo<TvProfiles> { inclusive = true } } },
                onAdd = { navController.navigate(Setup(SetupMode.ADD_PROFILE)) { launchSingleTop = true } },
                onSignIn = { navController.navigate(Setup.unlock(it)) { launchSingleTop = true } },
            )
        }
    }
}

@Composable
private fun Placeholder() = TvMessage(stringResource(R.string.tv_placeholder))

/**
 * To a section of the rail. Each one stands right on the start page, so Back from any of them leads there, and from there out
 * of the app.
 */
private fun NavHostController.openSection(section: TvSection) {
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
