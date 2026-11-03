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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
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

/** The page shown in the detail pane, kept across recreation so it can move onto the outer back stack. */
data class PaneSelection(val kind: String, val a: String, val b: String = "") {
    fun route(): Any = when (kind) {
        KIND_BROWSE -> Browse(BrowseKind.valueOf(a), b)
        KIND_COLLECTION -> Collection(a)
        else -> ItemDetail(a)
    }

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_BROWSE = "browse"
        const val KIND_COLLECTION = "collection"

        val Saver = listSaver<PaneSelection?, String>(
            save = { s -> if (s == null) emptyList() else listOf(s.kind, s.a, s.b) },
            restore = { l -> if (l.size == 3) PaneSelection(l[0], l[1], l[2]) else null },
        )

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

/**
 * Library and search on wide windows: the list on the start side, the details of what was tapped beside it.
 *
 * `ListDetailPaneScaffold` is used for the layout only (fixed value: both panes shown) when the window offers two
 * partitions; the detail pane hosts its own nested `NavHost` with the app's `ItemDetail` / `Browse` / `Collection`
 * routes, so their ViewModels (which read the route from their `SavedStateHandle`) work unchanged and the outer
 * navigation is not touched. Opening from the list replaces the pane's pages, links inside the pane push onto it,
 * "back" pops it. On one pane the list opens pages on [outer] as before; when the window shrinks to one pane (rotation,
 * folding) the page that was shown in the pane moves onto [outer].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ListDetailPane(
    outer: NavHostController,
    list: @Composable (PaneOpener) -> Unit,
    modifier: Modifier = Modifier,
    /** Share of the width the list takes beside the details. */
    listShare: Float = 0.42f,
    directive: PaneScaffoldDirective = rememberPaneDirective(),
    twoPane: Boolean = directive.isTwoPane,
    paneGraph: NavGraphBuilder.(NavHostController) -> Unit = { appPaneGraph(it) },
) {
    var selection by rememberSaveable(stateSaver = PaneSelection.Saver) { mutableStateOf<PaneSelection?>(null) }
    // A new nested controller after the pane's page moved to the outer stack, so a later wide window starts empty.
    var generation by rememberSaveable { mutableIntStateOf(0) }
    if (!twoPane) {
        LaunchedEffect(Unit) {
            selection?.let {
                outer.navigate(it.route())
                selection = null
                generation++
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
    key(generation) {
        val inner = rememberNavController()
        val top by inner.currentBackStackEntryAsState()
        LaunchedEffect(top) { if (top != null) selection = PaneSelection.of(top) }
        // From the list: the pane shows just that page (its own back stack starts fresh).
        fun replace(route: Any) = inner.navigate(route) { popUpTo<PaneEmpty>() }
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
