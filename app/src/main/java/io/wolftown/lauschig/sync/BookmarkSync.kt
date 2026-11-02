package io.wolftown.lauschig.sync

import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.absapi.me.BookmarkBody
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.bookmarkTimePath
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.db.dao.BookmarkDao
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Pushes the bookmark outbox, then pulls the server list and merges it (rules in [BookmarkRules]). A 404/400 for one
 * bookmark (title gone or not accessible: 404/403/400) keeps that op pending without blocking the others; any other failure
 * propagates and leaves the outbox as it was, so the next run retries.
 */
class BookmarkSync(
    private val api: Flow<MeApi?>,
    private val dao: BookmarkDao,
) {
    @Inject constructor(apiProvider: ApiProvider, dao: BookmarkDao) : this(apiProvider.meApi, dao)

    /** One sync at a time per process (the worker and the bookmark-only work may overlap). */
    suspend fun sync(serverId: String) = lock.withLock {
        if (DeviceLibrary.isLocal(serverId)) return@withLock // device bookmarks never leave the device
        val api = api.first() ?: return@withLock
        for (row in dao.pending(serverId)) {
            if (!BookmarkRules.isSyncable(row.itemKey)) continue
            try {
                push(api, row)
            } catch (e: HttpException) {
                // This title is gone, refused or rejected for this user: keep its op, let the others through.
                if (e.code() == 404 || e.code() == 400 || e.code() == 403) continue
                throw e
            }
        }
        val server = fetchAll(api) ?: return@withLock
        dao.mergeFromServer(serverId, server)
    }

    private suspend fun push(api: MeApi, row: BookmarkEntity) {
        when (row.pending) {
            BookmarkPending.NONE -> Unit
            BookmarkPending.UPSERT -> {
                val body = BookmarkBody(row.timeSec, BookmarkRules.serverTitle(row.timeSec, row.note))
                if (row.onServer) {
                    try {
                        api.updateBookmark(row.itemKey, body)
                    } catch (e: HttpException) {
                        // Deleted elsewhere while edited here: the edit wins, the bookmark comes back.
                        if (e.code() != 404) throw e
                        api.createBookmark(row.itemKey, body)
                    }
                } else {
                    api.createBookmark(row.itemKey, body) // idempotent per time on the server
                }
                dao.markPushed(row.serverId, row.itemKey, row.timeSec, row.rev)
            }
            BookmarkPending.DELETE -> {
                try {
                    api.deleteBookmark(row.itemKey, bookmarkTimePath(row.timeSec))
                } catch (e: HttpException) {
                    if (e.code() != 404) throw e // already gone: done
                }
                dao.deletePushed(row.serverId, row.itemKey, row.timeSec, row.rev)
            }
        }
    }

    private companion object {
        /** Process-wide: every instance shares it. */
        val lock = Mutex()
    }

    /** `/api/me/bookmarks`, or the list inside `/api/me` on servers without that route. Null = unknown, no merge. */
    private suspend fun fetchAll(api: MeApi): List<BookmarkDto>? = try {
        api.bookmarks().bookmarks
    } catch (e: HttpException) {
        if (e.code() != 404) throw e
        api.me().bookmarks
    }
}
