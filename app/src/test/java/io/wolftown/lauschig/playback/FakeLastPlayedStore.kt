package io.wolftown.lauschig.playback

/** One title per server id (and one for the device), in memory. */
open class FakeLastPlayedStore : LastPlayedStore {
    val titles = HashMap<String, LastPlayed>()
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
    override suspend fun set(last: LastPlayed) { titles[last.serverId] = last }
    override suspend fun forget(serverId: String) {
        onForget(serverId)
        titles.remove(serverId)
    }
}
