package io.wolftown.lauschig.sync

import androidx.room.withTransaction
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.PlaylistItemDto
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.CollectionDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.CollectionEntity
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Caches the server's collections (per book library) and the user's playlists in Room. A failed fetch keeps the
 * previous cache of that library / of the playlists; the first failure is rethrown at the end. Playlist episodes
 * the device does not know yet are added (row + audio file), so they can be played without opening the podcast.
 */
class CollectionSync(
    private val api: Flow<LibraryApi?>,
    private val libraries: LibraryDao,
    private val collections: CollectionDao,
    private val episodes: EpisodeDao,
    private val audioFiles: AudioFileDao,
    private val stamps: SyncStamps = InMemorySyncStamps(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    @Inject constructor(
        apiProvider: ApiProvider,
        libraries: LibraryDao,
        collections: CollectionDao,
        episodes: EpisodeDao,
        audioFiles: AudioFileDao,
        stamps: SyncStamps,
        db: LauschigDatabase,
    ) : this(apiProvider.libraryApi, libraries, collections, episodes, audioFiles, stamps, System::currentTimeMillis, { block -> db.withTransaction { block() } })

    /**
     * Background variant: the server sends every collection with fully expanded books, so the periodic sync refreshes
     * the cache at most once per [MIN_INTERVAL_MS] after a successful run (a failure does not count). Opening the
     * collections/playlists UI calls [sync] directly.
     */
    suspend fun syncIfDue(serverId: String) {
        val last = stamps.get(stampKey(serverId))
        if (last != null && clock() - last in 0 until MIN_INTERVAL_MS) return
        sync(serverId)
    }

    /** Fetches now (unthrottled); a fully successful run is recorded for [syncIfDue]. */
    suspend fun sync(serverId: String) {
        val api = api.first() ?: return
        var failure: Exception? = null
        fun fail(e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
        for (lib in libraries.getAll(serverId).filter { it.mediaType.equals("book", ignoreCase = true) }) {
            try {
                val fetched = api.collections(lib.id).results
                collections.replaceCollections(
                    serverId, lib.id,
                    fetched.map { CollectionEntity(serverId, it.id, CollectionKind.COLLECTION, lib.id, it.name, it.description?.takeIf { d -> d.isNotBlank() }, it.lastUpdate) },
                    fetched.associate { c -> c.id to c.books.map { it.id }.distinct() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
        try {
            val lists = api.playlists().playlists
            transaction {
                lists.flatMap { it.items }.filter { it.episodeId != null }.distinctBy { it.libraryItemId to it.episodeId }.forEach { storeEpisode(serverId, it) }
                collections.replacePlaylists(
                    serverId,
                    lists.map { CollectionEntity(serverId, it.id, CollectionKind.PLAYLIST, it.libraryId, it.name, it.description?.takeIf { d -> d.isNotBlank() }, it.lastUpdate) },
                    lists.associate { p -> p.id to p.items.map { it.key() }.distinct() },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
        failure?.let { throw it }
        stamps.set(stampKey(serverId), clock())
    }

    companion object {
        const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private fun stampKey(serverId: String) = "collections:$serverId"
    }

    private fun PlaylistItemDto.key(): String = episodeId?.let { PlayableKey.episode(libraryItemId, it) } ?: libraryItemId

    /** Adds an unknown episode with its file; a known one (maybe downloaded, flagged recent) stays as it is. */
    private suspend fun storeEpisode(serverId: String, item: PlaylistItemDto) {
        val ep = item.episode ?: return
        val inserted = episodes.insertIgnore(listOf(ep.toEntity(serverId, item.libraryItemId))).single() != -1L
        val file = ep.audioFile ?: return
        val key = PlayableKey.episode(item.libraryItemId, ep.id)
        if (!inserted && audioFiles.forItem(serverId, key).isNotEmpty()) return
        audioFiles.replaceFromServer(
            serverId, key,
            listOf(AudioFileEntity(serverId, key, 1, file.ino, file.duration, file.metadata.size, file.metadata.ext.removePrefix("."))),
        )
    }
}
