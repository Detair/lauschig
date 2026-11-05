package io.wolftown.lauschig.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

/** What happens with the next volume of a series. */
enum class NextVolumeMode {
    /** At the end of a volume, offer the next one (play / download). Default. */
    SUGGEST,
    /** Additionally download the next volume once about 80 % of the current one is heard. */
    PREFETCH,
}

/** How many of the newest unheard episodes per podcast are downloaded after a sync. */
enum class AutoDownloadMode(val count: Int) {
    OFF(0), NEWEST_1(1), NEWEST_3(3), NEWEST_5(5),
}

/** App preferences that are not download-specific (those live in DownloadSettings). Device-local only. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.appDataStore

    val nextVolumeMode: Flow<NextVolumeMode> = store.data.map { prefs ->
        prefs[NEXT_VOLUME]?.let { runCatching { NextVolumeMode.valueOf(it) }.getOrNull() } ?: NextVolumeMode.SUGGEST
    }

    /** Weekly reminder about finished titles that are still downloaded. Default on. */
    val cleanupReminder: Flow<Boolean> = store.data.map { it[CLEANUP_REMINDER] ?: true }

    /** Titles ("serverId:itemId") the user does not want to be reminded about again. */
    val cleanupIgnored: Flow<Set<String>> = store.data.map { it[CLEANUP_IGNORED] ?: emptySet() }

    /** Fill series gaps from the Audible catalog (only ASINs are sent). Default on. */
    val missingVolumesEnabled: Flow<Boolean> = store.data.map { it[MISSING_VOLUMES] ?: true }

    /** Audible catalog region (`de`, `us`, ...). Default `de`. */
    val audibleRegion: Flow<String> = store.data.map { it[AUDIBLE_REGION] ?: io.wolftown.lauschig.external.AudibleRegions.DEFAULT }

    /** Auto-download of new podcast episodes. Default off. */
    val autoDownload: Flow<AutoDownloadMode> = store.data.map { prefs ->
        prefs[AUTO_DOWNLOAD]?.let { runCatching { AutoDownloadMode.valueOf(it) }.getOrNull() } ?: AutoDownloadMode.OFF
    }

    /** Episode keys the auto-download already queued once, so a download the user removed does not come back. */
    suspend fun autoDownloaded(): Set<String> = store.data.first()[AUTO_DOWNLOADED] ?: emptySet()

    suspend fun setAutoDownload(mode: AutoDownloadMode) {
        store.edit { it[AUTO_DOWNLOAD] = mode.name }
    }

    suspend fun setAutoDownloaded(keys: Set<String>) {
        store.edit { it[AUTO_DOWNLOADED] = keys }
    }

    suspend fun setMissingVolumesEnabled(enabled: Boolean) {
        store.edit { it[MISSING_VOLUMES] = enabled }
    }

    /** Ignores codes that are not an Audible region. */
    suspend fun setAudibleRegion(region: String) {
        if (region !in io.wolftown.lauschig.external.AudibleRegions.all) return
        store.edit { it[AUDIBLE_REGION] = region }
    }

    suspend fun setNextVolumeMode(mode: NextVolumeMode) {
        store.edit { it[NEXT_VOLUME] = mode.name }
    }

    suspend fun setCleanupReminder(enabled: Boolean) {
        store.edit { it[CLEANUP_REMINDER] = enabled }
    }

    suspend fun setCleanupIgnored(key: String, ignored: Boolean) {
        store.edit { prefs ->
            val cur = prefs[CLEANUP_IGNORED] ?: emptySet()
            prefs[CLEANUP_IGNORED] = if (ignored) cur + key else cur - key
        }
    }

    /** Drops every entry of [serverId] from [cleanupIgnored] (its data was purged). */
    suspend fun forgetCleanupIgnored(serverId: String) {
        store.edit { prefs -> prefs[CLEANUP_IGNORED] = prefs[CLEANUP_IGNORED].orEmpty().filterNot { it.startsWith("$serverId:") }.toSet() }
    }

    private companion object {
        val AUTO_DOWNLOAD = stringPreferencesKey("auto_download_episodes")
        val AUTO_DOWNLOADED = stringSetPreferencesKey("auto_downloaded_episodes")
        val NEXT_VOLUME = stringPreferencesKey("next_volume_mode")
        val CLEANUP_REMINDER = booleanPreferencesKey("cleanup_reminder")
        val MISSING_VOLUMES = booleanPreferencesKey("missing_volumes_enabled")
        val AUDIBLE_REGION = stringPreferencesKey("audible_region")
        val CLEANUP_IGNORED = stringSetPreferencesKey("cleanup_ignored")
    }
}
