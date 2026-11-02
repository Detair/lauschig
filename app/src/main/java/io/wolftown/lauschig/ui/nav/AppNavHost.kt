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
import io.wolftown.lauschig.cleanup.CleanupRoute
import io.wolftown.lauschig.settings.SettingsRoute
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.browse.BrowseRoute
import io.wolftown.lauschig.ui.collections.CollectionRoute
import io.wolftown.lauschig.ui.podcast.ItemDetailEntry
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.library.LibraryRoute
import io.wolftown.lauschig.ui.player.PlayerRoute
import io.wolftown.lauschig.ui.discover.DiscoverRoute
import io.wolftown.lauschig.ui.search.SearchRoute
import io.wolftown.lauschig.ui.setup.SetupRoute
import io.wolftown.lauschig.ui.start.StartRoute

/**
 * [startDestination] is [Setup] when there is no account and [Start] otherwise (decided from the local
 * account store, never from the network). After a successful login [Setup] is removed from the back stack.
 * [Setup] is also opened on top of the app from the "sign in again" hint; it is then pre-filled from the account.
 */
@Composable
fun AppNavHost(navController: NavHostController, startDestination: Any, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = startDestination, modifier = modifier) {
        composable<Start> { StartRoute(onOpenItem = { navController.navigate(ItemDetail(it)) }, onOpenSettings = { navController.navigate(Settings) }, onOpenLibrary = { navController.navigateToTab(TopLevelTab.Library) }, onOpenDiscover = { navController.navigateToTab(TopLevelTab.Discover) }) }
        composable<Settings> { SettingsRoute(onBack = { navController.popBackStack() }, onOpenCleanup = { navController.navigate(Cleanup) }) }
        composable<Cleanup> { CleanupRoute(onBack = { navController.popBackStack() }) }
        composable<Library> { LibraryRoute(onOpenItem = { navController.navigate(ItemDetail(it)) }, onOpenCollection = { navController.navigate(Collection(it)) }) }
        composable<Collection> { CollectionRoute(onBack = { navController.popBackStack() }, onOpenItem = { navController.navigate(ItemDetail(it)) }) }
        composable<Discover> {
            DiscoverRoute(
                onOpenItem = { navController.navigate(ItemDetail(it)) },
                onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
            )
        }
        composable<Search> {
            SearchRoute(
                onOpenItem = { navController.navigate(ItemDetail(it)) },
                onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
            )
        }
        composable<ItemDetail> {
            ItemDetailEntry(
                onBack = { navController.popBackStack() },
                onOpenItem = { navController.navigate(ItemDetail(it)) },
                onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
            )
        }
        composable<Browse> {
            BrowseRoute(
                onBack = { navController.popBackStack() },
                onOpenItem = { navController.navigate(ItemDetail(it)) },
                onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
            )
        }
        composable<Player> {
            PlayerRoute(
                onClose = { navController.popBackStack() },
                // The details replace the player, so "back" from them returns to where the player was opened from.
                onOpenDetails = { id -> navController.navigate(ItemDetail(PlayableKey.parse(id).libraryItemId)) { popUpTo<Player> { inclusive = true } } },
            )
        }
        composable<Setup> {
            SetupRoute(onDone = {
                // Re-auth (opened on top of the app): just go back. First setup: replace Setup with Start.
                if (navController.previousBackStackEntry != null) navController.popBackStack()
                else navController.navigate(Start) { popUpTo<Setup> { inclusive = true } }
            })
        }
    }
}

@Composable
fun NavHostController.isOnSetup(): Boolean =
    currentBackStackEntryAsState().value?.destination?.hasRoute<Setup>() == true

@Composable
fun NavHostController.isOnPlayer(): Boolean =
    currentBackStackEntryAsState().value?.destination?.hasRoute<Player>() == true

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
