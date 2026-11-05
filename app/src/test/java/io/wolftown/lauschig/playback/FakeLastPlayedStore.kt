package io.wolftown.lauschig.playback

import io.wolftown.lauschig.local.DeviceLibrary

/** One title per server id, the device's shared one under its id, and each account's device title, in memory. */
open class FakeLastPlayedStore : LastPlayedStore {
    val titles = HashMap<String, LastPlayed>()
    val deviceTitles = HashMap<String, LastPlayed>()
    /** Called on [forget] (tests that record the order of a sign-out). */
    var onForget: (String) -> Unit = {}

    /** For a test about one title: the only one there is. */
    var value: LastPlayed?
        get() = titles.values.singleOrNull()
        set(v) {
            titles.clear()
            if (v != null) titles[v.serverId] = v
        }

    override suspend fun get(serverId: String) = titles[serverId]
    override suspend fun deviceTitleOf(account: String) = deviceTitles[account]
    override suspend fun set(last: LastPlayed, account: String?) {
        if (DeviceLibrary.isLocal(last.serverId) && account == null) {
            titles[last.serverId] = last
            return
        }
        if (DeviceLibrary.isLocal(last.serverId)) deviceTitles[account!!] = last else titles[last.serverId] = last
        titles.remove(DeviceLibrary.SERVER_ID)
    }
    override suspend fun forget(serverId: String) {
        onForget(serverId)
        val server = titles.remove(serverId)
        deviceTitles.remove(serverId)?.takeIf { own -> server == null || own.pausedAt > server.pausedAt }?.let { own ->
            val shared = titles[DeviceLibrary.SERVER_ID]
            if (shared == null || shared.pausedAt < own.pausedAt) titles[DeviceLibrary.SERVER_ID] = own
        }
    }
}
