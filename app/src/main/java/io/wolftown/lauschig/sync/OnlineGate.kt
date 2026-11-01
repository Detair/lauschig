package io.wolftown.lauschig.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Waits for the connection check to resolve (a cold process starts with a placeholder Offline). */
fun interface OnlineGate {
    /** True once online; false if still offline after the grace period. */
    suspend fun awaitOnline(): Boolean
}

class ApiOnlineGate @Inject constructor(private val apiProvider: ApiProvider) : OnlineGate {
    override suspend fun awaitOnline(): Boolean =
        withTimeoutOrNull(10.seconds) { apiProvider.connection.first { it is ConnectionState.Online } } != null
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun onlineGate(impl: ApiOnlineGate): OnlineGate
}
