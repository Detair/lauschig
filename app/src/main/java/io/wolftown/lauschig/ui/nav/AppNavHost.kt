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
import io.wolftown.lauschig.ui.placeholder.ComingSoonScreen
import io.wolftown.lauschig.ui.start.StartRoute

/** Task 11 will route to [Setup] when there is no account; for now the graph always starts at [Start]. */
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Start, modifier = modifier) {
        composable<Start> { StartRoute() }
        // Library, detail, player and setup screens arrive in later tasks.
        composable<Library> { ComingSoonScreen() }
        composable<Discover> { ComingSoonScreen() }
        composable<Search> { ComingSoonScreen() }
        composable<ItemDetail> { ComingSoonScreen() }
        composable<Player> { ComingSoonScreen() }
        composable<Setup> { ComingSoonScreen() }
    }
}

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
