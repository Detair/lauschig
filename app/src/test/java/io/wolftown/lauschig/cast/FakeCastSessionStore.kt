package io.wolftown.lauschig.cast

class FakeCastSessionStore : CastSessionStore {
    val open = LinkedHashSet<Pair<String, String>>()
    override suspend fun add(serverId: String, sessionId: String) { open += serverId to sessionId }
    override suspend fun remove(serverId: String, sessionId: String) { open -= serverId to sessionId }
    override suspend fun all() = open.toList()
}
