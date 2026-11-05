package io.wolftown.lauschig.download

import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Tidies one account's download directories on every mounted volume (run by the janitor at app start). Two leftovers an
 * SD card makes possible: a title deleted while its card was out (its directory came back, its row is gone) and a
 * finished title that also left a partial copy on a card that was out while it downloaded again. A title whose download
 * is still waiting, running or paused is never touched: its `.part` files are live.
 */
class DownloadTidy(
    private val files: DownloadFiles,
    private val downloads: DownloadDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(files: DownloadFiles, downloads: DownloadDao) : this(files, downloads, Dispatchers.IO)

    /** Returns how many titles were tidied. */
    suspend fun tidy(serverId: String): Int {
        var tidied = 0
        for (item in withContext(io) { files.itemIds(serverId) }) {
            val row = downloads.get(serverId, item)
            if (row == null) {
                // Read again right before deleting: a download added meanwhile keeps its directory.
                withContext(io) { if (downloads.get(serverId, item) == null) files.deleteItem(serverId, item) }
                tidied++
            } else if (row.state == DownloadState.COMPLETE && withContext(io) { files.isOnSeveralVolumes(serverId, item) }) {
                withContext(io) { files.cleanupOrphans(serverId, item) }
                tidied++
            }
        }
        return tidied
    }
}
