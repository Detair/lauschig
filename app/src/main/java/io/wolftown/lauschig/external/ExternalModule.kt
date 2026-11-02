package io.wolftown.lauschig.external

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the account-less OkHttp client used for Audible (not the ABS one with the auth interceptor). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudibleHttp

@Module
@InstallIn(SingletonComponent::class)
object ExternalModule {
    @Provides @Singleton @AudibleHttp
    fun audibleClient(): OkHttpClient = AudibleClient.okHttp()

    @Provides @Singleton
    fun audibleEndpoints(): AudibleEndpoints = AudibleRegions.production

    @Provides @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()
}
