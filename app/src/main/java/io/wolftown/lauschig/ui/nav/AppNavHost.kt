package io.wolftown.lauschig.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.getValue
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.library.LibraryRoute
import io.wolftown.lauschig.ui.placeholder.ComingSoonScreen
import io.wolftown.lauschig.ui.setup.SetupRoute
import io.wolftown.lauschig.ui.start.StartRoute

/**
 * [startDestination] is [Setup] when there is no account and [Start] otherwise (decided from the local
 * account store, never from the network). After a successful login [Setup] is removed from the back stack.
 */
@Composable
fun AppNavHost(navController: NavHostController, startDestination: Any, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = startDestination, modifier = modifier) {
        composable<Start> { StartRoute() }
        // Library, detail, player and setup screens arrive in later tasks.
        composable<Library> { LibraryRoute(onOpenItem = { navController.navigate(ItemDetail(it)) }) }
        composable<Discover> { ComingSoonScreen() }
        composable<Search> { ComingSoonScreen() }
        composable<ItemDetail> { ComingSoonScreen() }
        composable<Player> { ComingSoonScreen() }
        composable<Setup> {
            SetupRoute(onDone = {
                navController.navigate(Start) { popUpTo<Setup> { inclusive = true } }
            })
        }
    }
}

@Composable
fun NavHostController.isOnSetup(): Boolean =
    currentBackStackEntryAsState().value?.destination?.hasRoute<Setup>() == true

@Composable
fun NavHostController.currentTab(): TopLevelTab? {
    val dest = currentBackStackEntryAsState().value?.destination
    return when {
        dest == null -> TopLevelTab.Start
        dest.hasRoute<Start>() -> TopLevelTab.Start
        dest.hasRoute<Library>() -> TopLevelTab.Library
        dest.hasRoute<Discover>() -> TopLevelTab.Discover
        dest.hasRoute<Search>() -> TopLevelTab.Search
        else -> null
    }
}

fun NavHostController.navigateToTab(tab: TopLevelTab) {
    val route: Any = when (tab) {
        TopLevelTab.Start -> Start
        TopLevelTab.Library -> Library
        TopLevelTab.Discover -> Discover
        TopLevelTab.Search -> Search
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
