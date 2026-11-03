package io.wolftown.lauschig.account

import io.wolftown.lauschig.auto.CoverStore
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AccountDataDao
import io.wolftown.lauschig.db.dao.BookmarkDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What one account (server id) owns on this device: its Room rows ([AccountDataDao]), its downloads
 * (`downloads/<serverId>`, partial files and covers included), the car's cover cache of that server and its sync
 * stamps. The device library (`DeviceLibrary.SERVER_ID`) is never account data and cannot be purged.
 */
class AccountData(
    private val dao: AccountDataDao,
    private val progress: ProgressDao,
    private val sessions: SessionDao,
    private val bookmarks: BookmarkDao,
    private val files: DownloadFiles,
    private val stamps: SyncStamps,
    /** Drops the car's cached server covers of a server ([CoverStore.forgetServer]). Blocking. */
    private val forgetCovers: (serverId: String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(db: LauschigDatabase, files: DownloadFiles, stamps: SyncStamps, covers: CoverStore) :
        this(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), files, stamps, covers::forgetServer)

    /**
     * Changes the sync workers would still upload: dirty progress rows, unsynced offline sessions and pending bookmark
     * changes, with the same filters the syncs use (device keys and episode bookmarks never leave the device).
     */
    suspend fun unsynced(serverId: String): Int {
        if (DeviceLibrary.isLocal(serverId)) return 0
        val dirty = progress.dirty(serverId).count { !DeviceLibrary.isLocalKey(it.itemId) }
        val open = sessions.unsynced(serverId).count { !DeviceLibrary.isLocalKey(it.itemId) }
        val marks = bookmarks.pending(serverId).count { BookmarkRules.isSyncable(it.itemKey) }
        return dirty + open + marks
    }

    /** Bytes on disk of the server's downloads (complete and partial files, covers); 0 for none or an invalid id. */
    suspend fun downloadBytes(serverId: String): Long = withContext(io) { files.serverBytes(serverId) }

    /** Keeps everything, but waiting and running downloads become PAUSED (their work is gone after sign-out). */
    suspend fun pauseDownloads(serverId: String) = dao.pauseActiveDownloads(serverId)

    /**
     * Deletes everything of [serverId]: the sync stamps first (a kill afterwards costs at most an extra fetch, never a
     * stamp that hides missing rows), then the rows (nothing then points at the files), then the files and caches.
     */
    suspend fun purge(serverId: String) {
        require(!DeviceLibrary.isLocal(serverId)) { "the device library is not account data" }
        stamps.removeServer(serverId)
        dao.deleteServer(serverId)
        withContext(io) {
            files.deleteServer(serverId)
            forgetCovers(serverId)
        }
    }
}
