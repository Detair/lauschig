package io.wolftown.lauschig.tv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvSettingsDataStore by preferencesDataStore(name = "tv_settings")

/** Settings only the TV interface has. Device-local: a TV is one device, whoever listens. */
interface TvSettingsStore {
    /** Minutes without a key press after which the player goes black ("screen off"); 0 = never. Default [DEFAULT_SCREEN_OFF_MINUTES]. */
    val screenOffMinutes: Flow<Int>

    suspend fun setScreenOffMinutes(minutes: Int)

    companion object {
        const val DEFAULT_SCREEN_OFF_MINUTES = 5
    }
}

@Singleton
class DataStoreTvSettings internal constructor(private val store: DataStore<Preferences>) : TvSettingsStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context.tvSettingsDataStore)

    override val screenOffMinutes: Flow<Int> =
        store.data.map { it[SCREEN_OFF_MINUTES]?.coerceAtLeast(0) ?: TvSettingsStore.DEFAULT_SCREEN_OFF_MINUTES }

    override suspend fun setScreenOffMinutes(minutes: Int) {
        store.edit { it[SCREEN_OFF_MINUTES] = minutes.coerceAtLeast(0) }
    }

    private companion object {
        val SCREEN_OFF_MINUTES = intPreferencesKey("screen_off_minutes")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TvSettingsModule {
    @Binds abstract fun tvSettings(impl: DataStoreTvSettings): TvSettingsStore
}

/** For a page's route, which binds no view model of its own for one setting. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TvSettingsEntryPoint {
    fun tvSettings(): TvSettingsStore
}

/** The app's [TvSettingsStore] (Hilt), for routes; stateless screens take the values as parameters. */
@Composable
internal fun rememberTvSettings(): TvSettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, TvSettingsEntryPoint::class.java).tvSettings() }
}
