package io.wolftown.lauschig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * Owns every ViewModel of one test. [clear] cancels them like [ViewModelStore.clear] and then blocks until all
 * their viewModelScope coroutines have actually finished.
 *
 * Cancellation is asynchronous: a `flowOn(Dispatchers.Default)` producer or a Room query still unwinding on a
 * background thread resumes its collector through Dispatchers.Main when it completes. If that happens while the
 * test calls `Dispatchers.resetMain()` (or the next test `setMain()`), kotlinx-coroutines-test fails with
 * "Dispatchers.Main is used concurrently with setting it". So: create every ViewModel through this class (never
 * leave one uncleared) and call [clear] before closing the database and before `resetMain()`.
 *
 * Assumes Main is a test dispatcher (Unconfined), so nothing here needs the main looper to make progress.
 */
class TestViewModels {
    private val store = ViewModelStore()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val keys = AtomicInteger()

    fun <T : ViewModel> get(factory: ViewModelProvider.Factory, cls: KClass<T>): T =
        ViewModelProvider.create(store, factory)["vm${keys.incrementAndGet()}", cls]
            .also { jobs += it.viewModelScope.coroutineContext.job }

    /** Takes ownership of a ViewModel the test constructed directly. */
    inline fun <reified T : ViewModel> own(vm: T): T = get(viewModelFactory { initializer { vm } }, T::class)

    fun clear() {
        store.clear()
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        jobs.clear()
    }
}
