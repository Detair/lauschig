package io.wolftown.lauschig.download

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadDataStore by preferencesDataStore(name = "download_settings")

/**
 * Download preferences. [wifiOnly] (default `true`) maps to the WorkManager network constraint
 * `UNMETERED` (else `CONNECTED`) when a download is enqueued. Phase 1: changing it does not retarget
 * work that is already queued; it applies to downloads enqueued afterwards.
 */
@Singleton
class DownloadSettings @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.downloadDataStore

    val wifiOnly: Flow<Boolean> = store.data.map { it[WIFI_ONLY] ?: true }

    suspend fun setWifiOnly(value: Boolean) {
        store.edit { it[WIFI_ONLY] = value }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    }
}
