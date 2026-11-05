package io.wolftown.lauschig.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavHostController
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

/**
 * The pages of one account: [content] gets a navigation controller of their own, and for its `NavHost` the owner of their
 * view models (`LocalViewModelStoreOwner`). Another [owner] ends all of it in one step: the page on screen, its remembered
 * state, the tabs saved away and every view model. Nothing of an account outlives it – a page left on screen would ask for
 * its covers with the next account's token (`GuardedCallFactory`), and a page that reads its account once would show the
 * old one. A switch does not animate; the next account's start tab is simply there.
 *
 * A rotation and a process death keep the pages: their state is saved under the owner's key, and [scope] outlives the rotation.
 */
@Composable
fun AccountPages(owner: String?, scope: AccountPagesScope, content: @Composable (NavHostController, ViewModelStoreOwner) -> Unit) {
    key(owner) {
        val navController = rememberNavController()
        val models = remember(scope) {
            val store = scope.storeFor(owner)
            object : ViewModelStoreOwner { override val viewModelStore = store }
        }
        DisposableEffect(Unit) {
            scope.settle(owner)
            onDispose { navController.retire() }
        }
        content(navController, models)
    }
}

/** Off the host's lifecycle: the activity would otherwise keep every navigation of its lifetime as an observer. */
private fun NavHostController.retire() = setLifecycleOwner(NoHost)

/** A lifecycle at its end: observers get no event. */
private object NoHost : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.CREATED
        currentState = Lifecycle.State.DESTROYED
    }
}
