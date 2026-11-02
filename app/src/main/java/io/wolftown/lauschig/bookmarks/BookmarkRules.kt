package io.wolftown.lauschig.bookmarks

import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.playback.PlayableKey
import java.util.Locale

/** What a pull changes locally: rows to write and rows to remove. */
data class BookmarkMergePlan(val upserts: List<BookmarkEntity>, val deletes: List<BookmarkEntity>)

/**
 * Pure rules for bookmarks: the local outbox (save/delete coalesce on the row) and the merge of the server list.
 *
 * Conflict rules (server identity = libraryItemId + time):
 * - A row with a pending local change always wins over the pulled state (it is newer than what we pulled; the push
 *   sends it). A pending edit whose bookmark was removed elsewhere is re-created (PATCH 404 -> POST): nothing lost.
 * - A synced row takes the server title; a synced row the server no longer has was deleted elsewhere and goes.
 * - Server rows we do not have are inserted as synced.
 * - Episode bookmarks are device-local (ABS bookmarks carry no episode id) and are never merged.
 */
object BookmarkRules {
    fun isSyncable(itemKey: String): Boolean = PlayableKey.parse(itemKey).episodeId == null

    /** The server title for an empty note: the clock text of the position (`02:03`, `1:02:03`). */
    fun defaultTitle(timeSec: Double): String {
        val total = timeSec.coerceAtLeast(0.0).toLong()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun serverTitle(timeSec: Double, note: String?): String = note?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(timeSec)

    fun noteFromTitle(timeSec: Double, title: String): String? = title.trim().takeIf { it.isNotEmpty() && it != defaultTitle(timeSec) }

    /**
     * "Set a bookmark here" at [timeSec]: a new row, or – when a bookmark already sits on that second – a merge that
     * never loses its note: an empty or equal new note changes nothing, a different one is appended on a new line.
     * A tombstone at that second is replaced by a fresh bookmark (its old note was deleted on purpose).
     */
    fun add(existing: BookmarkEntity?, serverId: String, itemKey: String, timeSec: Double, note: String?, now: Long): BookmarkEntity {
        if (existing == null || existing.pending == BookmarkPending.DELETE) return save(existing?.copy(note = null), serverId, itemKey, timeSec, note, now)
        val new = note?.trim()?.takeIf { it.isNotEmpty() } ?: return existing
        val old = existing.note
        if (old == new) return existing
        return save(existing, serverId, itemKey, timeSec, if (old.isNullOrEmpty()) new else "$old\n$new", now)
    }

    /** Create or edit the note as given (also revives a tombstone). Books become pending; episode bookmarks stay local. */
    fun save(existing: BookmarkEntity?, serverId: String, itemKey: String, timeSec: Double, note: String?, now: Long): BookmarkEntity {
        val pending = if (isSyncable(itemKey)) BookmarkPending.UPSERT else BookmarkPending.NONE
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        return existing?.copy(note = clean, pending = pending, rev = existing.rev + 1)
            ?: BookmarkEntity(serverId, itemKey, timeSec, clean, createdAt = now, pending = pending)
    }

    /**
     * Null = remove the row now (episode bookmarks, which never leave the device). A book bookmark always becomes a
     * tombstone, even if no create was confirmed yet: the POST may have reached the server with its response lost, and
     * the push treats a 404 on DELETE as done.
     */
    fun delete(existing: BookmarkEntity): BookmarkEntity? =
        if (!isSyncable(existing.itemKey)) null
        else existing.copy(pending = BookmarkPending.DELETE, rev = existing.rev + 1)

    /** [local] = all rows of the server (any key); [server] = the full server list. */
    fun merge(serverId: String, local: List<BookmarkEntity>, server: List<BookmarkDto>): BookmarkMergePlan {
        val remote = server.associateBy { it.libraryItemId to it.time }
        val mine = local.filter { isSyncable(it.itemKey) }.associateBy { it.itemKey to it.timeSec }
        val upserts = mutableListOf<BookmarkEntity>()
        val deletes = mutableListOf<BookmarkEntity>()
        for ((id, row) in mine) {
            if (row.pending != BookmarkPending.NONE) continue
            val r = remote[id]
            when {
                r == null -> if (row.onServer) deletes += row
                else -> {
                    val note = noteFromTitle(r.time, r.title)
                    if (note != row.note || !row.onServer) upserts += row.copy(note = note, onServer = true)
                }
            }
        }
        for ((id, r) in remote) {
            if (id in mine) continue
            upserts += BookmarkEntity(serverId, r.libraryItemId, r.time, noteFromTitle(r.time, r.title), r.createdAt, BookmarkPending.NONE, onServer = true)
        }
        return BookmarkMergePlan(upserts, deletes)
    }
}
