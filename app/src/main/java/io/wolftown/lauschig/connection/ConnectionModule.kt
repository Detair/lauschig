package io.wolftown.lauschig.connection

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds abstract fun networkInfo(impl: AndroidNetworkInfo): NetworkInfo
    @Binds abstract fun probe(impl: OkHttpReachabilityProbe): ReachabilityProbe
}
