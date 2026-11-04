package io.wolftown.lauschig.sync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Starts a one-time library/progress sync without waiting for it. */
interface SyncTrigger {
    fun syncNow()

    /** Pushes and pulls only the bookmarks (after a local bookmark change). */
    fun syncBookmarks() = syncNow()

    /** Progress and sessions only, a few seconds from now (after a pause or the end of a title); repeated calls debounce. */
    fun syncProgress() {}

    /** A full sync in about a minute (after an admin started a library scan, which the server runs in the background). */
    fun syncLibrarySoon() = syncNow()

    /** True while a [syncNow] run is waiting (for a network) or running; "Refresh library" shows it and starts none then. */
    fun nowRunning(): Flow<Boolean> = flowOf(false)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncTriggerModule {
    @Binds abstract fun syncTrigger(impl: SyncScheduler): SyncTrigger
}
