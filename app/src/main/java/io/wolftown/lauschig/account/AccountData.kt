package io.wolftown.lauschig.account

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.auto.CoverStore
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.cleanup.AutoDeleteAccountState
import io.wolftown.lauschig.cleanup.FinishedSeenStore
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AccountDataDao
import io.wolftown.lauschig.db.dao.BookmarkDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.download.DataStoreVolumePurges
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.VolumePurges
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * What one account (server id) owns on this device: its Room rows ([AccountDataDao]), its downloads
 * (`downloads/<serverId>`, partial files and covers included), the car's cover cache of that server and its sync
 * stamps. The device library (`DeviceLibrary.SERVER_ID`) is never account data and cannot be purged.
 */
interface AccountData {
    /**
     * Changes the sync workers would still upload: dirty progress rows, unsynced offline sessions and pending bookmark
     * changes, with the same filters the syncs use (device keys and episode bookmarks never leave the device).
     */
    suspend fun unsynced(serverId: String): Int

    /**
     * Bytes on disk of the server's downloads (complete and partial files, covers); 0 for none or an invalid id. A walk
     * of its download directory, never Room, so it still answers when the database does not.
     */
    suspend fun downloadBytes(serverId: String): Long

    /** Keeps everything, but waiting and running downloads become PAUSED (their work is gone after sign-out). */
    suspend fun pauseDownloads(serverId: String)

    /**
     * Deletes everything of [serverId]; throws if anything could not be deleted (rows or files), so the caller can
     * keep a way back to it. Idempotent: a retry finishes what is left.
     */
    suspend fun purge(serverId: String)

    /**
     * The rows and the sync stamps once more (late writes of a stopping service, worker or statistics page – a stamp
     * left behind would describe rows that are gone); idempotent. Files are the purge's.
     */
    suspend fun deleteRows(serverId: String)

    /** True if [serverId] has any row left. */
    suspend fun hasRows(serverId: String): Boolean

    /** True if some of [serverId]'s downloads are on a volume that is not there now (an SD card that is out). */
    suspend fun downloadsOnMissingVolume(serverId: String): Boolean = false
}

class DeviceAccountData(
    private val dao: AccountDataDao,
    private val progress: ProgressDao,
    private val sessions: SessionDao,
    private val bookmarks: BookmarkDao,
    private val files: DownloadFiles,
    private val stamps: SyncStamps,
    /** Drops the car's cached server covers of a server ([CoverStore.forgetServer]). Blocking. */
    private val forgetCovers: (serverId: String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Download roots on a card that was out at the purge: the purge waits for them. */
    private val volumePurges: VolumePurges = VolumePurges.None,
    /** The automatic clean-up's "first seen finished" times of the server ([FinishedSeenStore.forget]). */
    private val forgetSeen: suspend (serverId: String) -> Unit = {},
    /** The automatic clean-up's setting, open questions and keep entries of the server ([AutoDeleteAccountState.forget]). */
    private val forgetAutoDelete: suspend (serverId: String) -> Unit = {},
    /** The server's answer about the Audible catalog. */
    private val forgetAudibleAnswer: suspend (serverId: String) -> Unit = {},
) : AccountData {
    @Inject constructor(
        db: LauschigDatabase, files: DownloadFiles, stamps: SyncStamps, covers: CoverStore, volumePurges: DataStoreVolumePurges, seen: FinishedSeenStore,
        autoDelete: AutoDeleteAccountState, app: io.wolftown.lauschig.settings.AppSettings,
    ) : this(
        db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), files, stamps, covers::forgetServer, Dispatchers.IO, volumePurges,
        seen::forget, autoDelete::forget, app::clearMissingVolumes,
    )

    override suspend fun unsynced(serverId: String): Int {
        if (DeviceLibrary.isLocal(serverId)) return 0
        val dirty = progress.dirty(serverId).count { !DeviceLibrary.isLocalKey(it.itemId) }
        val open = sessions.unsynced(serverId).count { !DeviceLibrary.isLocalKey(it.itemId) }
        val marks = bookmarks.pending(serverId).count { BookmarkRules.isSyncable(it.itemKey) }
        return dirty + open + marks
    }

    override suspend fun downloadBytes(serverId: String): Long = withContext(io) { files.serverBytes(serverId) }

    override suspend fun pauseDownloads(serverId: String) = dao.pauseActiveDownloads(serverId)

    /**
     * The sync stamps first (a kill afterwards costs at most an extra fetch, never a stamp that hides missing rows),
     * then the automatic clean-up's setting, questions and keep entries (a purge cut short leaves the clean-up off) and the
     * answer about the Audible catalog, then
     * the rows (nothing then points at the files), then the files and caches. Downloads on a card that is out are
     * noted first (the rows say where they are): the purge fails until the card was back and they are gone, so the
     * caller keeps it pending and the janitor finishes it.
     */
    override suspend fun purge(serverId: String) {
        require(!DeviceLibrary.isLocal(serverId)) { "the device library is not account data" }
        val away = withContext(io) { files.missingRoots(serverId) }
        volumePurges.add(serverId, away)
        stamps.removeServer(serverId)
        forgetAutoDelete(serverId)
        forgetAudibleAnswer(serverId)
        dao.deleteServer(serverId)
        forgetSeen(serverId)
        withContext(io) {
            val gone = files.deleteServer(serverId)
            forgetCovers(serverId)
            val waiting = volumePurges.finish(serverId) { root -> files.deleteServerAt(root, serverId) }
            if (!gone) throw IOException("the downloads of $serverId could not all be deleted")
            if (waiting.isNotEmpty()) throw IOException("downloads on a card that is out are deleted once it is back")
        }
    }

    override suspend fun downloadsOnMissingVolume(serverId: String): Boolean = withContext(io) { files.missingRoots(serverId).isNotEmpty() }

    override suspend fun deleteRows(serverId: String) {
        require(!DeviceLibrary.isLocal(serverId)) { "the device library is not account data" }
        stamps.removeServer(serverId)
        dao.deleteServer(serverId)
    }

    override suspend fun hasRows(serverId: String): Boolean = dao.hasRows(serverId)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountDataModule {
    @Binds abstract fun accountData(impl: DeviceAccountData): AccountData
}
