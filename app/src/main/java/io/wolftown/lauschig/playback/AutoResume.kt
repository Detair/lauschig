package io.wolftown.lauschig.playback

import io.wolftown.lauschig.local.DeviceLibrary
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The last title": the one last played on this device ([LastPlayedStore], written by the service on every pause
 * and stop), not the newest progress row (that may come from another device or from a progress action). Skipped
 * when it belongs to another (or no signed-in) account, is gone or orphaned, finished, or hidden from "continue
 * listening". A device title resumes whatever the account, also signed out.
 * Local DB only, no network.
 */
@Singleton
class LastTitleStarter @Inject constructor(
    private val accounts: AccountStore,
    private val store: LastPlayedStore,
    private val progress: ProgressDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val player: PlayerController,
) {
    data class Last(val serverId: String, val itemId: String, val pausedAt: Long)

    suspend fun last(): Last? {
        // Signed out nothing resumes (play keys, the car, headphones): only Setup is on the phone. Signed in, a device
        // title is this device's whatever the account; anything else must belong to the signed-in server.
        val account = accounts.account.first()?.serverId ?: return null
        val stored = store.get()?.takeIf { DeviceLibrary.isLocal(it.serverId) || it.serverId == account } ?: return null
        val serverId = stored.serverId
        val key = PlayableKey.parse(stored.itemId)
        val item = items.get(serverId, key.libraryItemId)
        if (item == null || item.orphaned) return null
        if (key.episodeId != null && episodes.get(serverId, key.libraryItemId, key.episodeId) == null) return null
        val p = progress.get(serverId, stored.itemId)
        if (p != null && (p.isFinished || p.hideFromContinue)) return null
        return Last(serverId, stored.itemId, stored.pausedAt)
    }

    /**
     * Main thread. Plays the last title unless something plays or loads, or it paused more than [maxAgeMs] ago.
     * Returns true if it started something.
     */
    suspend fun resumeLast(maxAgeMs: Long? = null, now: Long = System.currentTimeMillis()): Boolean {
        val s = player.state.value
        if (s.isPlaying || s.isLoading) return false
        val last = last() ?: return false
        if (maxAgeMs != null && (now - last.pausedAt < 0 || now - last.pausedAt > maxAgeMs)) return false
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
        val mode = audio?.mode
        val go = policy.onDevicesAdded(
            devices,
            enabled = callback != null,
            isPlayingOrLoading = s.isPlaying || s.isLoading,
            lastPausedAt = last?.pausedAt,
            now = System.currentTimeMillis(),
            inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE,
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
