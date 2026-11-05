package io.wolftown.lauschig.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.switchedDownloadsDataStore by preferencesDataStore(
    name = "switched_downloads",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The downloads a profile switch paused, per account: when the account is active again exactly these go on, under the
 * usual rules (the Wi-Fi setting). What the user paused stays paused: it is not noted here. On disk, because the way back
 * can be days and processes later; losing the note costs a tap on "resume", nothing else.
 */
@Singleton
class SwitchedDownloads(
    private val store: DataStore<Preferences>,
    private val rows: DownloadDao,
    private val manager: DownloadManager,
) {
    @Inject constructor(@ApplicationContext context: Context, rows: DownloadDao, manager: DownloadManager) :
        this(context.switchedDownloadsDataStore, rows, manager)

    /** Notes the waiting and running downloads of [serverId]; call before they are paused. */
    suspend fun rememberActive(serverId: String) = remember(serverId, rows.activeKeys(serverId).toSet())

    suspend fun remember(serverId: String, keys: Set<String>) {
        if (keys.isNotEmpty()) store.edit { it[key(serverId)] = it[key(serverId)].orEmpty() + keys }
    }

    /**
     * [serverId] is active again: its noted downloads go on. Also those still waiting or running: a process that died
     * during the switch left them so, without their work; [DownloadManager.enqueue] gives it back and leaves work that is
     * still there alone. What is complete, failed or removed by now stays as it is. A download that could not be queued
     * stays noted for the next start.
     */
    suspend fun resume(serverId: String) {
        val noted = store.data.first()[key(serverId)].orEmpty()
        if (noted.isEmpty()) return
        val done = noted.filterTo(HashSet()) { item ->
            try {
                val state = rows.get(serverId, item)?.state
                if (state == DownloadState.PAUSED || state == DownloadState.QUEUED || state == DownloadState.RUNNING) manager.enqueue(serverId, item)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a download did not go on after the switch", e)
                false
            }
        }
        store.edit { p ->
            val left = p[key(serverId)].orEmpty() - done
            if (left.isEmpty()) p.remove(key(serverId)) else p[key(serverId)] = left
        }
    }

    /** The account is gone or signed out (its downloads then wait for the user, as after every sign-out). */
    suspend fun forget(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }

    private fun key(serverId: String) = stringSetPreferencesKey("paused_by_switch:$serverId")

    private companion object {
        const val TAG = "SwitchedDownloads"
    }
}
