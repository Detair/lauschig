package io.wolftown.lauschig.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Starts a one-time library/progress sync without waiting for it. */
interface SyncTrigger {
    fun syncNow()

    /** Pushes and pulls only the bookmarks (after a local bookmark change). */
    fun syncBookmarks() = syncNow()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncTriggerModule {
    @Binds abstract fun syncTrigger(impl: SyncScheduler): SyncTrigger
}
