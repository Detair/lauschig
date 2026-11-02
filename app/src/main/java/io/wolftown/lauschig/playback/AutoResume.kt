package io.wolftown.lauschig.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The last title": the newest unfinished progress row of the signed-in account (local DB only, no network).
 * [resumeLast] plays it; a loaded, paused title simply continues (see [PlayerController.play]).
 */
@Singleton
class LastTitleStarter @Inject constructor(
    private val accounts: AccountStore,
    private val progress: ProgressDao,
    private val player: PlayerController,
) {
    data class Last(val serverId: String, val itemId: String, val lastUpdate: Long)

    suspend fun last(): Last? {
        val serverId = accounts.account.first()?.serverId ?: return null
        val row = progress.newestUnfinished(serverId) ?: return null
        return Last(serverId, row.itemId, row.lastUpdate)
    }

    /** Main thread. Does nothing while something plays or loads. Returns true if it started something. */
    suspend fun resumeLast(): Boolean {
        val s = player.state.value
        if (s.isPlaying || s.isLoading) return false
        val last = last() ?: return false
        player.play(last.serverId, last.itemId)
        return true
    }
}

/**
 * Resumes the last title when a car or headphones connect (setting, default off). Uses an [AudioDeviceCallback]
 * (no permission) while the app process lives; registered only while the setting is on. Rules: [AutoResumePolicy].
 *
 * Limits: a dead process gets no callback, and Android 12+ may refuse to start the playback service in the
 * foreground from the background; then nothing plays (Media3 reports it, the service logs it).
 */
@Singleton
class AutoResumer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlaybackSettings,
    private val starter: LastTitleStarter,
    private val player: PlayerController,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val policy = AutoResumePolicy()
    private var callback: AudioDeviceCallback? = null

    /** [scope] must be main-bound (player access). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.autoResumeOnConnect.distinctUntilChanged().collect { on -> if (on) register(scope) else unregister() }
        }
    }

    private fun register(scope: CoroutineScope) {
        val am = audio ?: return
        if (callback != null) return
        policy.initialDevices(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id to it.type })
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val devices = addedDevices.filter { it.isSink }.map { it.id to it.type }
                scope.launch { onAdded(devices) }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                policy.onDevicesRemoved(removedDevices.map { it.id })
            }
        }
        callback = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun unregister() {
        callback?.let { audio?.unregisterAudioDeviceCallback(it) }
        callback = null
    }

    private suspend fun onAdded(devices: List<Pair<Int, Int>>) {
        val s = player.state.value
        // Only a relevant output needs the last title; the policy still has to see every device it did not know.
        val last = if (devices.any { AutoResumePolicy.isResumeOutput(it.second) }) starter.last() else null
        val go = policy.onDevicesAdded(
            devices,
            enabled = callback != null,
            isPlayingOrLoading = s.isPlaying || s.isLoading,
            lastPausedAt = last?.lastUpdate,
            now = System.currentTimeMillis(),
        )
        if (go) {
            Log.i(TAG, "audio output connected, resuming the last title")
            starter.resumeLast()
        }
    }

    private companion object {
        const val TAG = "AutoResumer"
    }
}
