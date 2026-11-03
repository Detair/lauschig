package io.wolftown.lauschig.ui.nav

import io.wolftown.lauschig.ui.downloads.DownloadsRoute
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
import io.wolftown.lauschig.settings.NavigationSettingsRoute
import io.wolftown.lauschig.settings.SettingsRoute
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.adaptive.AdoptOuterPageOnGrow
import io.wolftown.lauschig.ui.adaptive.ListDetailPane
import io.wolftown.lauschig.ui.adaptive.ReadableWidth
import io.wolftown.lauschig.ui.browse.BrowseRoute
import io.wolftown.lauschig.ui.connection.ConnectionStatusRoute
import io.wolftown.lauschig.admin.ServerAdminRoute
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
 * [startDestination] is [Setup] when there is no account and the chosen start tab otherwise (decided from the local
 * stores, never from the network); the first sign-in then leads to [homeRoute] (see [finishSetup]). After a successful login [Setup] is removed from the back stack.
 * [Setup] is also opened on top of the app from the "sign in again" hint; it is then pre-filled from the account.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    /** The user's start screen (route of the chosen start tab), where the first sign-in leads. */
    homeRoute: Any = Start,
) {
    // Rotating to a wide window with a title open: it moves into the library's (or search's) detail pane.
    AdoptOuterPageOnGrow(navController)
    NavHost(navController, startDestination = startDestination, modifier = modifier) {
        composable<Start> { StartRoute(onOpenItem = { navController.navigate(ItemDetail(it)) }, onOpenSettings = { navController.navigate(Settings) }, onOpenLibrary = { navController.navigateToTab(TopLevelTab.Library) }, onOpenDiscover = { navController.navigateToTab(TopLevelTab.Discover) }) }
        composable<Settings> {
            ReadableWidth {
                SettingsRoute(
                    onBack = { navController.popBackStack() },
                    onOpenCleanup = { navController.navigate(Cleanup) },
                    onOpenDownloads = { navController.navigate(Downloads) },
                    onOpenConnection = { navController.navigate(ConnectionStatus) },
                    onOpenAdmin = { navController.navigate(ServerAdmin) },
                    onOpenNavigation = { navController.navigate(NavigationSettings) },
                )
            }
        }
        composable<NavigationSettings> { ReadableWidth { NavigationSettingsRoute(onBack = { navController.popBackStack() }) } }
        composable<ConnectionStatus> { ReadableWidth { ConnectionStatusRoute(onBack = { navController.popBackStack() }) } }
        composable<ServerAdmin> { ReadableWidth { ServerAdminRoute(onBack = { navController.popBackStack() }) } }
        composable<Downloads> {
            // An episode's details are its podcast's page.
            ReadableWidth {
                DownloadsRoute(onBack = { navController.popBackStack() }, onOpenItem = { key -> navController.navigate(ItemDetail(PlayableKey.parse(key).libraryItemId)) })
            }
        }
        composable<Cleanup> { ReadableWidth { CleanupRoute(onBack = { navController.popBackStack() }) } }
        composable<Library> {
            // Wide windows: the details of a title open beside the library (see ListDetailPane).
            ListDetailPane(navController, listShare = 0.45f, list = { open ->
                LibraryRoute(
                    onOpenItem = open.openItem,
                    onOpenCollection = open.openCollection,
                    onOpenDownloads = { navController.navigate(Downloads) { launchSingleTop = true } },
                )
            })
        }
        composable<Collection> { CollectionRoute(onBack = { navController.popBackStack() }, onOpenItem = { navController.navigate(ItemDetail(it)) }) }
        composable<Discover> {
            DiscoverRoute(
                onOpenItem = { navController.navigate(ItemDetail(it)) },
                onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
            )
        }
        composable<Search> {
            ListDetailPane(navController, listShare = 0.4f, list = { open -> SearchRoute(onOpenItem = open.openItem, onOpenBrowse = open.openBrowse) })
        }
        composable<ItemDetail> {
            ReadableWidth {
                ItemDetailEntry(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigate(ItemDetail(it)) },
                    onOpenBrowse = { kind, name -> navController.navigate(Browse(kind, name)) },
                )
            }
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
            ReadableWidth {
                SetupRoute(onDone = { navController.finishSetup(homeRoute) })
            }
        }
    }
}

/**
 * After a sign-in. Re-auth (opened on top of the app): just go back. First setup: Setup is replaced by the user's start
 * screen [home], which also becomes the graph's start destination, so "back" from another tab returns to it and the
 * tab switches pop up to it instead of piling up.
 */
fun NavHostController.finishSetup(home: Any) {
    if (previousBackStackEntry != null) {
        popBackStack()
        return
    }
    graph.setStartDestination(home)
    navigate(home) { popUpTo<Setup> { inclusive = true } }
}

/**
 * Signed out while the app runs: only [Setup] is left, so "back" never returns to the old account's pages. Already
 * the only page (a start without an account): nothing changes. The next sign-in leads to the start tab ([finishSetup]).
 */
fun NavHostController.showSignIn() {
    if (currentDestination?.hasRoute<Setup>() == true && previousBackStackEntry == null) return
    navigate(Setup) { popUpTo(graph.id) { inclusive = true } }
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
    navigate(tab.route()) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
