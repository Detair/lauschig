package io.wolftown.lauschig.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.browse.BrowseRoute
import io.wolftown.lauschig.ui.collections.CollectionRoute
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.Collection
import io.wolftown.lauschig.ui.nav.ItemDetail
import io.wolftown.lauschig.ui.nav.Library
import io.wolftown.lauschig.ui.nav.Search
import io.wolftown.lauschig.ui.podcast.ItemDetailEntry
import kotlinx.serialization.Serializable

/** The detail pane before anything was chosen. */
@Serializable data object PaneEmpty

/** What a list opens: a title, an entity page (author, series, …) or a collection. */
class PaneOpener(
    val openItem: (itemId: String) -> Unit,
    val openBrowse: (BrowseKind, String) -> Unit,
    val openCollection: (id: String) -> Unit,
)

/** A page of the detail pane, as a value that fits a `SavedStateHandle` ([toSaved] / [fromSaved]) and names its route. */
data class PaneSelection(val kind: String, val a: String, val b: String = "") {
    fun route(): Any = when (kind) {
        KIND_BROWSE -> Browse(BrowseKind.valueOf(a), b)
        KIND_COLLECTION -> Collection(a)
        else -> ItemDetail(a)
    }

    fun toSaved(): ArrayList<String> = arrayListOf(kind, a, b)

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_BROWSE = "browse"
        const val KIND_COLLECTION = "collection"

        fun fromSaved(saved: List<String>?): PaneSelection? = saved?.takeIf { it.size == 3 }?.let { PaneSelection(it[0], it[1], it[2]) }

        fun of(entry: NavBackStackEntry?): PaneSelection? {
            val dest = entry?.destination ?: return null
            return when {
                dest.hasRoute<ItemDetail>() -> PaneSelection(KIND_ITEM, entry.toRoute<ItemDetail>().itemId)
                dest.hasRoute<Browse>() -> entry.toRoute<Browse>().let { PaneSelection(KIND_BROWSE, it.kind.name, it.name) }
                dest.hasRoute<Collection>() -> PaneSelection(KIND_COLLECTION, entry.toRoute<Collection>().id)
                else -> null
            }
        }
    }
}

/** The routes whose composable is a [ListDetailPane] host (see `AppNavHost`: keep the two in step). */
fun NavDestination.isPaneHost(): Boolean = hasRoute<Library>() || hasRoute<Search>()

/*
 * What the host keeps in its back stack entry (`SavedStateHandle`) and not with `rememberSaveable`: that one is keyed by
 * the composition position, which `AppScaffold` changes when the navigation rail appears or disappears with a rotation
 * (a large phone: bottom bar in portrait, rail and two panes in landscape), so the page would be lost on the way.
 */
/** The page shown in the detail pane, so it can move onto the outer stack when the window shrinks. */
private const val SELECTION = "listDetailPane.selection"
/** A page the outer stack hands to the pane ([AdoptOuterPageIntoPane]); the pane takes it and clears it. */
private const val HANDOFF = "listDetailPane.handoff"
/**
 * Counts the pane's page moves onto the outer stack; the nested controller is keyed by it, so a later wide window starts
 * with a fresh one. Kept here too: after the rail flipped, the position-keyed saved state of an older controller would
 * be found again under generation 0 and bring a page back that the user had backed out of.
 */
private const val GENERATION = "listDetailPane.generation"
/** Set by a re-tap on the host's tab ([closeDetailPane]): the pane goes back to empty, the list stays. */
private const val CLOSE = "listDetailPane.close"

/**
 * Asks the [ListDetailPane] of this host entry to show its list alone again (the detail pane back to empty, its pages
 * gone): the tab's own page, as a re-tap on the tab expects. One pane: nothing to close.
 */
fun NavBackStackEntry.closeDetailPane() {
    if (destination.isPaneHost()) savedStateHandle[CLOSE] = true
}

/**
 * Library and search on wide windows: the list on the start side, the details of what was tapped beside it.
 *
 * `ListDetailPaneScaffold` is used for the layout only (fixed value: both panes shown) when the window offers two
 * partitions; the detail pane hosts its own nested `NavHost` with the app's `ItemDetail` / `Browse` / `Collection`
 * routes, so their ViewModels (which read the route from their `SavedStateHandle`) work unchanged and the outer
 * navigation is not touched. Opening from the list replaces the pane's pages, links inside the pane push onto it,
 * "back" pops it. On one pane the list opens pages on [outer] as before.
 *
 * The page the pane shows and the page on [outer] are two homes of the same thing, and the window decides which one
 * is used: when it shrinks to one pane (rotation, folding) the pane's page moves onto [outer] (here); while it offers
 * two panes, an outer page directly above this host moves into the pane ([AdoptOuterPageIntoPane]). [host] is the
 * back stack entry of this composable.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ListDetailPane(
    outer: NavHostController,
    host: NavBackStackEntry,
    list: @Composable (PaneOpener) -> Unit,
    modifier: Modifier = Modifier,
    /** Share of the width the list takes beside the details. */
    listShare: Float = 0.42f,
    directive: PaneScaffoldDirective = rememberPaneDirective(),
    twoPane: Boolean = directive.isTwoPane,
    paneGraph: NavGraphBuilder.(NavHostController) -> Unit = { appPaneGraph(it) },
) {
    val saved = host.savedStateHandle
    val close by remember(saved) { saved.getStateFlow(CLOSE, false) }.collectAsState()
    if (!twoPane) {
        // No pane to close: a request from the tab bar must not close a later pane's handed-over page.
        LaunchedEffect(close) { if (close) saved[CLOSE] = false }
        LaunchedEffect(Unit) {
            // The pane's page, or one handed over just before the window shrank again, goes onto the outer stack.
            val page = PaneSelection.fromSaved(saved.remove<ArrayList<String>>(HANDOFF) ?: saved[SELECTION])
            saved[SELECTION] = null
            page?.let {
                outer.navigate(it.route())
                saved[GENERATION] = (saved.get<Int>(GENERATION) ?: 0) + 1
            }
        }
        list(
            PaneOpener(
                openItem = { outer.navigate(ItemDetail(it)) },
                openBrowse = { k, n -> outer.navigate(Browse(k, n)) },
                openCollection = { outer.navigate(Collection(it)) },
            ),
        )
        return
    }
    // `getStateFlow` returns a new wrapper on every call; collecting a new one each time would restart the collector.
    val handed by remember(saved) { saved.getStateFlow<ArrayList<String>?>(HANDOFF, null) }.collectAsState()
    val generation by remember(saved) { saved.getStateFlow(GENERATION, 0) }.collectAsState()
    key(generation) {
        val inner = rememberNavController()
        val top by inner.currentBackStackEntryAsState()
        LaunchedEffect(top) { if (top != null) saved[SELECTION] = PaneSelection.of(top)?.toSaved() }
        // From the list: the pane shows just that page (its own back stack starts fresh).
        fun replace(route: Any) = inner.navigate(route) { popUpTo<PaneEmpty>() }
        // Once the nested graph is up (top != null), the handed-over page takes the pane.
        LaunchedEffect(handed, top) {
            val page = PaneSelection.fromSaved(handed)
            if (page != null && top != null) {
                replace(page.route())
                saved[HANDOFF] = null
            }
        }
        LaunchedEffect(close, top) {
            if (close && top != null) {
                inner.popBackStack(PaneEmpty, inclusive = false)
                saved[CLOSE] = false
            }
        }
        val opener = PaneOpener(
            openItem = { replace(ItemDetail(it)) },
            openBrowse = { k, n -> replace(Browse(k, n)) },
            openCollection = { replace(Collection(it)) },
        )
        ListDetailPaneScaffold(
            directive = directive,
            value = ThreePaneScaffoldValue(primary = PaneAdaptedValue.Expanded, secondary = PaneAdaptedValue.Expanded, tertiary = PaneAdaptedValue.Hidden),
            listPane = { AnimatedPane(Modifier.preferredWidth(listShare).testTag("listPane")) { list(opener) } },
            detailPane = {
                AnimatedPane(Modifier.testTag("detailPane")) {
                    NavHost(inner, startDestination = PaneEmpty) {
                        composable<PaneEmpty> { EmptyPane() }
                        paneGraph(inner)
                    }
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * While the window offers two panes ([twoPane]), the page on top of [outer] – an `ItemDetail`, `Browse` or `Collection` –
 * that sits directly above a [ListDetailPane] host ([isPaneHost]) is popped and shown in that host's detail pane
 * instead: the page does not move away from where it is seen, it only changes home. This is a state, not an event, so
 * it holds however it comes about: a rotation or unfolding (also with the activity recreated, whatever the navigation
 * rail does), the player that covered the page being closed, a tab restored with its page, a link, a notification or
 * the player's "Details anzeigen" while the window is already wide.
 *
 * Pages above anything else (Start, Downloads, Settings, …) never move, and neither does a page deeper in a chain
 * ([Library, A, B]: B stays, A follows when B is gone). Place it beside the NavHost of [outer]. The window cannot be
 * two panes and one at once, so this and the shrink move of [ListDetailPane] never answer each other.
 */
@Composable
fun AdoptOuterPageIntoPane(outer: NavHostController, twoPane: Boolean = rememberPaneDirective().isTwoPane) {
    val top by outer.currentBackStackEntryAsState()
    LaunchedEffect(twoPane, top) {
        val page = top?.takeIf { twoPane && outer.currentBackStackEntry == it }?.let { PaneSelection.of(it) } ?: return@LaunchedEffect
        val host = outer.previousBackStackEntry?.takeIf { it.destination.isPaneHost() && it.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) } ?: return@LaunchedEffect
        host.savedStateHandle[HANDOFF] = page.toSaved()
        outer.popBackStack()
    }
}

/** The app's pages inside the detail pane; everything they open stays in the pane. */
fun NavGraphBuilder.appPaneGraph(pane: NavHostController) {
    composable<ItemDetail> {
        ItemDetailEntry(
            onBack = { pane.popBackStack() },
            onOpenItem = { pane.navigate(ItemDetail(it)) },
            onOpenBrowse = { kind, name -> pane.navigate(Browse(kind, name)) },
        )
    }
    composable<Browse> {
        BrowseRoute(
            onBack = { pane.popBackStack() },
            onOpenItem = { pane.navigate(ItemDetail(it)) },
            onOpenBrowse = { kind, name -> pane.navigate(Browse(kind, name)) },
        )
    }
    composable<Collection> { CollectionRoute(onBack = { pane.popBackStack() }, onOpenItem = { pane.navigate(ItemDetail(it)) }) }
}

@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize().padding(32.dp).testTag("emptyPane"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Text(
                stringResource(R.string.pane_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
