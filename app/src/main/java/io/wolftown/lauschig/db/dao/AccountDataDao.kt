package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Every row one account (server id) owns, for sign-out (see `AccountData`). Each table with a `serverId` column is
 * listed in [SERVER_TABLES]; a test compares that list with the schema, so a new table cannot be forgotten. Rows of the
 * device library (`serverId = "device"`) and of other servers are never touched: every statement filters by the id.
 */
@Dao
abstract class AccountDataDao {
    @Query("DELETE FROM libraries WHERE serverId = :serverId") protected abstract suspend fun libraries(serverId: String)
    @Query("DELETE FROM items WHERE serverId = :serverId") protected abstract suspend fun items(serverId: String)
    @Query("DELETE FROM chapters WHERE serverId = :serverId") protected abstract suspend fun chapters(serverId: String)
    @Query("DELETE FROM audio_files WHERE serverId = :serverId") protected abstract suspend fun audioFiles(serverId: String)
    @Query("DELETE FROM progress WHERE serverId = :serverId") protected abstract suspend fun progress(serverId: String)
    @Query("DELETE FROM local_sessions WHERE serverId = :serverId") protected abstract suspend fun localSessions(serverId: String)
    @Query("DELETE FROM downloads WHERE serverId = :serverId") protected abstract suspend fun downloads(serverId: String)
    @Query("DELETE FROM episodes WHERE serverId = :serverId") protected abstract suspend fun episodes(serverId: String)
    @Query("DELETE FROM external_series WHERE serverId = :serverId") protected abstract suspend fun externalSeries(serverId: String)
    @Query("DELETE FROM title_audio WHERE serverId = :serverId") protected abstract suspend fun titleAudio(serverId: String)
    @Query("DELETE FROM bookmarks WHERE serverId = :serverId") protected abstract suspend fun bookmarks(serverId: String)
    @Query("DELETE FROM queue WHERE serverId = :serverId") protected abstract suspend fun queue(serverId: String)
    @Query("DELETE FROM history_sessions WHERE serverId = :serverId") protected abstract suspend fun historySessions(serverId: String)
    @Query("DELETE FROM collections WHERE serverId = :serverId") protected abstract suspend fun collections(serverId: String)
    @Query("DELETE FROM collection_entries WHERE serverId = :serverId") protected abstract suspend fun collectionEntries(serverId: String)

    /** All rows of [serverId] in one transaction: observers see the account either complete or gone. */
    @Transaction
    open suspend fun deleteServer(serverId: String) {
        libraries(serverId)
        items(serverId)
        chapters(serverId)
        audioFiles(serverId)
        progress(serverId)
        localSessions(serverId)
        downloads(serverId)
        episodes(serverId)
        externalSeries(serverId)
        titleAudio(serverId)
        bookmarks(serverId)
        queue(serverId)
        historySessions(serverId)
        collections(serverId)
        collectionEntries(serverId)
    }

    /** True if [serverId] has a row in any of [SERVER_TABLES]. */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM libraries WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM items WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM chapters WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM audio_files WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM progress WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM local_sessions WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM downloads WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM episodes WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM external_series WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM title_audio WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM bookmarks WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM queue WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM history_sessions WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM collections WHERE serverId = :serverId)
             OR EXISTS(SELECT 1 FROM collection_entries WHERE serverId = :serverId)""",
    )
    abstract suspend fun hasRows(serverId: String): Boolean

    /** Waiting or running downloads become PAUSED: their work is cancelled, the user resumes them after signing in again. */
    @Query("UPDATE downloads SET state = 'PAUSED', error = NULL WHERE serverId = :serverId AND state IN ('QUEUED', 'RUNNING')")
    abstract suspend fun pauseActiveDownloads(serverId: String)

    companion object {
        /** Every table with a `serverId` column, in the order [deleteServer] empties them. */
        val SERVER_TABLES = listOf(
            "libraries", "items", "chapters", "audio_files", "progress", "local_sessions", "downloads", "episodes",
            "external_series", "title_audio", "bookmarks", "queue", "history_sessions", "collections", "collection_entries",
        )
    }
}
