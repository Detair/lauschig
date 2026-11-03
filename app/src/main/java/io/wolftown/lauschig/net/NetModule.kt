package io.wolftown.lauschig.net

import dagger.Binds
import dagger.Module
import dagger.Provides
import android.content.Context
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.account.ResilientTokenCipher
import io.wolftown.lauschig.account.TinkTokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.DataStoreAccountStore
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.connection.AddressResolver
import io.wolftown.lauschig.connection.ConnectionLog
import io.wolftown.lauschig.connection.accountConnection
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.ReachabilityProbe
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountBindingsModule {
    @Binds abstract fun accountStore(impl: DataStoreAccountStore): AccountStore
}

@Module
@InstallIn(SingletonComponent::class)
object NetModule {
    @Provides @Singleton
    fun tokenCipher(@ApplicationContext context: Context): TokenCipher = ResilientTokenCipher(
        create = { TinkTokenCipher(context) },
        reset = { TinkTokenCipher.reset(context) },
    )

    @Provides @Singleton
    fun addressResolver(probe: ReachabilityProbe, net: NetworkInfo, log: ConnectionLog) = AddressResolver(probe, net, log = log)

    @Provides @Singleton
    fun apiProvider(store: AccountStore, resolver: AddressResolver): ApiProvider {
        // A server switch resets the shared state to Offline/checking first (never the old server's Online).
        val connection = accountConnection(store.account, resolver::states)
        return ApiProvider(connection, OkHttpClient(), store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
