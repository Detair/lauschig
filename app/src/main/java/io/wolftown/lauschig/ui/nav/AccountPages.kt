package io.wolftown.lauschig.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Holds the view models of the pages for as long as they belong to one account, across a rotation. The navigation library
 * keeps those of its back stack entries in the store it is given, and would keep them – also of the tabs it saved away –
 * until the activity ends: here the store goes with the account.
 */
@HiltViewModel
class AccountPagesScope @Inject constructor() : ViewModel() {
    private val stores = HashMap<String?, ViewModelStore>()

    /** The store for the pages of [owner] (null: nobody signed in). */
    internal fun storeFor(owner: String?): ViewModelStore = stores.getOrPut(owner) { ViewModelStore() }

    /** The pages of [owner] are on screen: the view models of all others end. */
    internal fun settle(owner: String?) {
        val others = stores.keys.filter { it != owner }
        others.forEach { stores.remove(it)?.clear() }
    }

    /** The activity ends for good. */
    internal fun ended() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }

    override fun onCleared() = ended()
}

/** The pages of one account, for [AccountNavHost]: their navigation and who holds their view models. */
@Stable
class AccountNavigation internal constructor(val controller: NavHostController, internal val models: ViewModelStoreOwner)

/**
 * The pages of one account: [content] gets their navigation, to be hosted in an [AccountNavHost]. Another [owner] ends all of
 * it in one step: the page on screen, its remembered state, the tabs saved away and the view models of all pages. Nothing of
 * the pages outlives the account – a page left on screen would ask for what the next account's rows say, and a page that reads
 * its account once would show the old one. A switch does not animate; the next account's start tab is simply there.
 *
 * Above the pages, and not theirs: the player layer's view models (`PlayerViewModel`, `ItemActionsViewModel`, `ComfortViewModel`,
 * `TitleExtrasViewModel`) and those of `AppRoot` are activity-wide. Each follows the account itself, per call or through
 * `flatMapLatest`, and holds nothing of it across a switch.
 *
 * A rotation and a process death keep the pages: their state is saved per owner, and [scope] outlives the rotation. What was saved
 * for an owner that is no longer the active one goes (the account changed while the app was away, and a recreation
 * followed): its pages never come back, the way back to it starts on its start tab like any other.
 */
@Composable
fun AccountPages(owner: String?, scope: AccountPagesScope, content: @Composable (AccountNavigation) -> Unit) {
    val states = rememberSaveableStateHolder()
    var shown by rememberSaveable { mutableStateOf(owner) }
    key(owner) {
        states.SaveableStateProvider(owner ?: NOBODY) {
            val navController = rememberNavController()
            val pages = remember(scope, navController) {
                val store = scope.storeFor(owner)
                AccountNavigation(navController, object : ViewModelStoreOwner { override val viewModelStore = store })
            }
            DisposableEffect(Unit) {
                scope.settle(owner)
                onDispose { navController.retire() }
            }
            content(pages)
        }
    }
    DisposableEffect(owner) {
        if (shown != owner) {
            states.removeState(shown ?: NOBODY)
            shown = owner
        }
        onDispose {}
    }
}

/** The saved state's key for the pages without an account; a server id is hex. */
private const val NOBODY = "nobody"

/**
 * The `NavHost` of an account's pages. Its back stack entries keep their view models in the account's store, which is what ends
 * them with it ([AccountPagesScope]).
 */
@Composable
internal fun AccountNavHost(pages: AccountNavigation, startDestination: Any, modifier: Modifier = Modifier, routes: NavGraphBuilder.() -> Unit) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides pages.models) {
        NavHost(
            pages.controller, startDestination = startDestination, modifier = modifier,
            // The navigation library's own fades everywhere, except that the page under the player stays put while the
            // player (PlayerOverlay) slides over it and is there at once when the player slides away.
            enterTransition = { fadeIn(tween(DEFAULT_FADE_MS)) },
            exitTransition = { if (targetState.destination.hasRoute<Player>()) ExitTransition.KeepUntilTransitionsFinished else fadeOut(tween(DEFAULT_FADE_MS)) },
            popEnterTransition = { if (initialState.destination.hasRoute<Player>()) EnterTransition.None else fadeIn(tween(DEFAULT_FADE_MS)) },
            popExitTransition = { fadeOut(tween(DEFAULT_FADE_MS)) },
            builder = routes,
        )
    }
}

/** The navigation library's default cross-fade, kept for every page but the player. */
private const val DEFAULT_FADE_MS = 700

/** Off the host's lifecycle: the activity would otherwise keep every navigation of its lifetime as an observer. */
private fun NavHostController.retire() = setLifecycleOwner(EndedHost())

/** A lifecycle at its end: its observers get no event. One each: it holds them, and goes with the navigation it was given to. */
private class EndedHost : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.CREATED
        currentState = Lifecycle.State.DESTROYED
    }
}
