package io.wolftown.lauschig.download

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadDataStore by preferencesDataStore(name = "download_settings")

/** "Download on play" while on mobile data: ask each time, download over it, or wait for Wi-Fi. */
enum class MeteredDownload { ASK, ALWAYS, WIFI }

/**
 * Deleting the download of a finished title by itself: never (default), right away, or once it has been finished for
 * [afterMs]. Only the files go; progress and listening history stay (see `FinishedDownloadCleaner`).
 */
enum class AutoDeleteMode(val afterMs: Long?) {
    NEVER(null),
    IMMEDIATELY(0),
    AFTER_3_DAYS(3 * DAY_MS),
    AFTER_7_DAYS(7 * DAY_MS),
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Download preferences. [wifiOnly] (default `true`) maps to the WorkManager network constraint
 * `UNMETERED` (else `CONNECTED`) when a download is enqueued. Phase 1: changing it does not retarget
 * work that is already queued; it applies to downloads enqueued afterwards.
 * [downloadOnPlay] (default `true`) and [meteredOnPlay] (default [MeteredDownload.ASK]) drive [PlayDownloadStarter].
 */
@Singleton
class DownloadSettings @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.downloadDataStore

    val wifiOnly: Flow<Boolean> = store.data.map { it[WIFI_ONLY] ?: true }

    suspend fun setWifiOnly(value: Boolean) {
        store.edit { it[WIFI_ONLY] = value }
    }

    val downloadOnPlay: Flow<Boolean> = store.data.map { it[DOWNLOAD_ON_PLAY] ?: true }

    suspend fun setDownloadOnPlay(value: Boolean) {
        store.edit { it[DOWNLOAD_ON_PLAY] = value }
    }

    val meteredOnPlay: Flow<MeteredDownload> =
        store.data.map { p -> p[METERED_ON_PLAY]?.let { v -> MeteredDownload.entries.firstOrNull { it.name == v } } ?: MeteredDownload.ASK }

    suspend fun setMeteredOnPlay(value: MeteredDownload) {
        store.edit { it[METERED_ON_PLAY] = value.name }
    }

    /**
     * Where new downloads go: the app's files directory on another volume ([DownloadVolume.path], e.g. an SD card), or
     * null for the internal storage (default). A volume that is not available makes downloads fall back to internal
     * ([VolumeDownloadRoots]); the choice itself stays, so they go there again once the card is back.
     */
    val storagePath: Flow<String?> = store.data.map { it[STORAGE_PATH]?.takeIf { p -> p.isNotBlank() } }

    /**
     * The automatic clean-up of [serverId]'s account: per account, so on a family device one account turning it on never
     * deletes another account's downloads. Every account starts at [AutoDeleteMode.NEVER]; no account: NEVER.
     */
    fun autoDeleteFinished(serverId: String?): Flow<AutoDeleteMode> = store.data.map { p ->
        serverId?.let { sid -> p[autoDeleteKey(sid)]?.let { v -> AutoDeleteMode.entries.firstOrNull { it.name == v } } } ?: AutoDeleteMode.NEVER
    }

    suspend fun setAutoDeleteFinished(serverId: String, mode: AutoDeleteMode) {
        store.edit { it[autoDeleteKey(serverId)] = mode.name }
    }

    /** [serverId]'s data was purged: signed in again, it starts at NEVER like any new account. */
    suspend fun clearAutoDelete(serverId: String) {
        store.edit { it.remove(autoDeleteKey(serverId)) }
    }

    /**
     * Before this setting was per account it was one value for the device: that value belongs to the account that is
     * signed in now ([activeServerId]); the others start at NEVER. Idempotent; signed out, the old value is dropped.
     */
    suspend fun migrateAutoDelete(activeServerId: String?) {
        store.edit { p ->
            val old = p[AUTO_DELETE] ?: return@edit
            if (activeServerId != null && p[autoDeleteKey(activeServerId)] == null) p[autoDeleteKey(activeServerId)] = old
            p.remove(AUTO_DELETE)
        }
    }

    private fun autoDeleteKey(serverId: String) = stringPreferencesKey("auto_delete_finished:$serverId")

    /** Tests: the device-wide value as an older version stored it. */
    internal suspend fun putLegacyAutoDelete(mode: AutoDeleteMode) {
        store.edit { it[AUTO_DELETE] = mode.name }
    }

    suspend fun setStoragePath(path: String?) {
        store.edit { if (path.isNullOrBlank()) it.remove(STORAGE_PATH) else it[STORAGE_PATH] = path }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val DOWNLOAD_ON_PLAY = booleanPreferencesKey("download_on_play")
        val METERED_ON_PLAY = stringPreferencesKey("metered_on_play")
        val STORAGE_PATH = stringPreferencesKey("storage_path")
        /** The device-wide value before it became per account ([migrateAutoDelete]). */
        val AUTO_DELETE = stringPreferencesKey("auto_delete_finished")
    }
}
