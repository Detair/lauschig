package io.wolftown.lauschig.queue

import io.wolftown.lauschig.db.entities.QueueEntity

/** Pure list operations of the queue; results are in order, positions are rewritten by the caller. */
object QueueOrder {
    fun add(current: List<QueueEntity>, serverId: String, itemKey: String, now: Long): List<QueueEntity> =
        add(current, serverId, listOf(itemKey), now)

    /** Appends every key not queued yet (also skipping duplicates within [keys]). */
    fun add(current: List<QueueEntity>, serverId: String, keys: List<String>, now: Long): List<QueueEntity> {
        val seen = current.mapTo(HashSet()) { it.itemKey }
        val added = keys.filter { seen.add(it) }.map { QueueEntity(serverId, it, 0, now) }
        return current + added
    }

    fun remove(current: List<QueueEntity>, itemKey: String): List<QueueEntity> = current.filter { it.itemKey != itemKey }

    fun move(current: List<QueueEntity>, itemKey: String, delta: Int): List<QueueEntity> {
        val from = current.indexOfFirst { it.itemKey == itemKey }
        if (from < 0 || delta == 0) return current
        val to = (from + delta).coerceIn(0, current.lastIndex)
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }
}
