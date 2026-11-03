package io.wolftown.lauschig.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetDataStore by preferencesDataStore(name = "widget_state")

/** The widget's state on this device; the playback service writes it, the widget reads it (also after a reboot). */
interface WidgetStore {
    /** Null until the service wrote a title. */
    val state: Flow<WidgetState?>
    suspend fun get(): WidgetState? = state.first()
    suspend fun set(state: WidgetState)
}

@Singleton
class DataStoreWidgetStore @Inject constructor(@ApplicationContext context: Context) : WidgetStore {
    private val store = context.widgetDataStore

    override val state: Flow<WidgetState?> = store.data.map { it.toState() }.distinctUntilChanged()

    override suspend fun set(state: WidgetState) {
        store.edit { p ->
            if (!state.hasTitle) {
                p.clear()
                return@edit
            }
            p[SERVER] = state.serverId!!
            p[KEY] = state.key!!
            p[TITLE] = state.title
            if (state.subtitle != null) p[SUBTITLE] = state.subtitle else p.remove(SUBTITLE)
            p[VERSION] = state.coverVersion
            p[PLAYING] = state.playing
            p[POSITION] = state.positionMs
            p[DURATION] = state.durationMs
        }
    }

    private fun Preferences.toState(): WidgetState? = WidgetState(
        serverId = this[SERVER] ?: return null,
        key = this[KEY] ?: return null,
        title = this[TITLE].orEmpty(),
        subtitle = this[SUBTITLE],
        coverVersion = this[VERSION] ?: 0,
        playing = this[PLAYING] ?: false,
        positionMs = this[POSITION] ?: 0,
        durationMs = this[DURATION] ?: 0,
    )

    private companion object {
        val SERVER = stringPreferencesKey("server_id")
        val KEY = stringPreferencesKey("key")
        val TITLE = stringPreferencesKey("title")
        val SUBTITLE = stringPreferencesKey("subtitle")
        val VERSION = longPreferencesKey("cover_version")
        val PLAYING = booleanPreferencesKey("playing")
        val POSITION = longPreferencesKey("position_ms")
        val DURATION = longPreferencesKey("duration_ms")
    }
}
