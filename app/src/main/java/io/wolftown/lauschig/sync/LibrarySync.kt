package io.wolftown.lauschig.sync

import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.EpisodeDto
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import androidx.room.withTransaction
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.ChapterDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.parseMinifiedSeries
import io.wolftown.lauschig.db.seriesSortKey
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Pulls libraries and items into Room. Local-only data (downloads, progress) is never touched. */
class LibrarySync(
    private val api: Flow<LibraryApi?>,
    private val libraries: LibraryDao,
    private val items: ItemDao,
    private val chapters: ChapterDao,
    private val audioFiles: AudioFileDao,
    private val progress: ProgressDao,
    private val downloads: DownloadDao,
    private val episodes: EpisodeDao,
    /** Runs the block in one DB transaction (rolled back on exception). */
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    @Inject constructor(
        apiProvider: ApiProvider,
        libraries: LibraryDao,
        items: ItemDao,
        chapters: ChapterDao,
        audioFiles: AudioFileDao,
        progress: ProgressDao,
        downloads: DownloadDao,
        episodes: EpisodeDao,
        db: LauschigDatabase,
    ) : this(apiProvider.libraryApi, libraries, items, chapters, audioFiles, progress, downloads, episodes, { block -> db.withTransaction { block() } })

    /**
     * Syncs every library. Offline (no API) is a no-op. A failing library does not stop the others, but
     * the first failure is rethrown at the end so callers can retry. Server-missing items are only removed
     * from a library whose pages were all fetched and add up to the reported total.
     */
    suspend fun syncAll(serverId: String) {
        val api = api.first() ?: return
        val remote = api.libraries().libraries
        libraries.upsertFromServer(
            remote.map { LibraryEntity(serverId, it.id, it.name, it.mediaType, it.displayOrder) },
        )
        var failure: Exception? = null
        for (lib in remote) {
            try {
                syncLibrary(api, serverId, lib.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
            if (lib.mediaType.equals("podcast", ignoreCase = true)) syncRecentEpisodes(api, serverId, lib.id)
        }
        failure?.let { throw it }
    }

    /** Marks the library's newest unheard episodes as `recent`. Best effort: a failure never fails the sync. */
    private suspend fun syncRecentEpisodes(api: LibraryApi, serverId: String, libraryId: String) {
        try {
            val fetched = api.recentEpisodes(libraryId, RECENT_LIMIT, 0).episodes.filter { it.libraryItemId != null }
            // Flags are reset only after a successful fetch, so a failure keeps the previous set.
            transaction {
                episodes.replaceRecent(serverId, libraryId, fetched.map { it.toEntity(serverId, it.libraryItemId!!, recent = true) })
                fetched.forEach { storeEpisodeFiles(serverId, it.libraryItemId!!, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep the previous recent set
        }
    }

    private suspend fun syncLibrary(api: LibraryApi, serverId: String, libraryId: String) {
        val before = items.getByLibrary(serverId, libraryId).associateBy { it.id }
        val seen = HashSet<String>()
        var page = 0
        var total: Int
        do {
            val result = api.items(libraryId, PAGE_SIZE, page, 1)
            total = result.total
            val mapped = result.results.map { it.toListEntity(serverId) }
            items.upsertFromList(mapped)
            val stale = mapped.filter { m -> before[m.id]?.let { isStale(it, m) } == true }.map { it.id }
            if (stale.isNotEmpty()) items.invalidateDetails(serverId, stale)
            val added = mapped.count { seen.add(it.id) }
            page++
            // A page without new ids (empty, or a server ignoring `page`) ends the loop; the count check below blocks deletion.
        } while (added > 0 && seen.size < total)

        if (seen.size != total) return
        val missing = before.keys - seen
        if (missing.isEmpty()) return
        val (keep, drop) = missing.partition { progress.hasAnyForItem(serverId, it) || downloads.hasAnyForItem(serverId, it) }
        if (keep.isNotEmpty()) items.setOrphaned(serverId, keep, true)
        if (drop.isNotEmpty()) items.deleteByIds(serverId, drop)
    }

    /**
     * Details are stale after a series change; podcasts (episode count > 0) also after a changed episode count or
     * `updatedAt`, so the podcast page picks up episodes added or removed on the server.
     */
    private fun isStale(old: ItemEntity, new: ItemEntity): Boolean {
        if (old.seriesName != new.seriesName) return true
        val podcast = old.numEpisodes > 0 || new.numEpisodes > 0
        return podcast && (old.numEpisodes != new.numEpisodes || old.updatedAt != new.updatedAt)
    }

    /** Same as [loadDetails]; reads better where only the episodes of a podcast are wanted. */
    suspend fun loadEpisodes(serverId: String, itemId: String) = loadDetails(serverId, itemId)

    /** Details for whatever a playable key points at: an episode key loads its podcast (and with it all of its episodes). */
    suspend fun loadDetailsForPlayable(serverId: String, key: String) = loadDetails(serverId, PlayableKey.parse(key).libraryItemId)

    /** Fetches the expanded item and stores details, chapters and audio files. */
    suspend fun loadDetails(serverId: String, itemId: String) {
        val api = api.first() ?: return
        val dto = api.item(itemId, 1)
        val meta = dto.media.metadata
        val ref = meta.series.firstOrNull()
        val fallback = if (ref == null) parseMinifiedSeries(meta.seriesName) else null
        val seq = ref?.sequence ?: fallback?.second
        val entity = dto.toListEntity(serverId).copy(
            description = meta.description,
            seriesId = ref?.id,
            seriesName = ref?.name ?: fallback?.first,
            seriesSequence = seq,
            seriesSortKey = seriesSortKey(seq),
        )
        // Atomic; the item row (detailsLoaded = true) goes last so a failure never leaves it marked loaded.
        transaction {
            if (dto.mediaType.equals("podcast", ignoreCase = true)) {
                episodes.replaceForItem(serverId, itemId, dto.media.episodes.map { it.toEntity(serverId, itemId) })
                dto.media.episodes.forEach { storeEpisodeFiles(serverId, itemId, it) }
            }
            chapters.replaceFromServer(
                serverId, itemId,
                dto.media.chapters.mapIndexed { i, c -> ChapterEntity(serverId, itemId, i, c.start, c.end, c.title) },
            )
            audioFiles.replaceFromServer(
                serverId, itemId,
                dto.media.audioFiles.sortedBy { it.index }.map {
                    AudioFileEntity(serverId, itemId, it.index, it.ino, it.duration, it.metadata.size, it.metadata.ext.removePrefix("."))
                },
            )
            items.upsertDetails(entity)
        }
    }

    /** The episode's single audio file (index 1) and chapters, stored under its playable key. */
    private suspend fun storeEpisodeFiles(serverId: String, itemId: String, ep: EpisodeDto) {
        val key = PlayableKey.episode(itemId, ep.id)
        // No audio file in the payload: keep what is stored (it may be downloaded).
        val file = ep.audioFile ?: return
        audioFiles.replaceFromServer(
            serverId, key,
            listOf(AudioFileEntity(serverId, key, 1, file.ino, file.duration, file.metadata.size, file.metadata.ext.removePrefix("."))),
        )
        chapters.replaceFromServer(serverId, key, ep.chapters.mapIndexed { i, c -> ChapterEntity(serverId, key, i, c.start, c.end, c.title) })
    }

    private fun LibraryItemDto.toListEntity(serverId: String): ItemEntity {
        val m = media.metadata
        val series = parseMinifiedSeries(m.seriesName)
        return ItemEntity(
            serverId = serverId,
            id = id,
            libraryId = libraryId,
            title = m.title,
            subtitle = m.subtitle,
            // Podcasts carry the author in `author`, books in `authorName`.
            author = m.authorName ?: m.author,
            narrator = m.narratorName,
            seriesName = series?.first,
            seriesSequence = series?.second,
            seriesSortKey = seriesSortKey(series?.second),
            durationSec = media.duration ?: 0.0,
            coverUpdatedAt = updatedAt,
            genres = m.genres,
            tags = media.tags,
            updatedAt = updatedAt,
            addedAt = addedAt,
            publishedYear = m.publishedYear?.takeIf { it.isNotBlank() },
            asin = m.asin?.takeIf { it.isNotBlank() },
            language = m.language?.takeIf { it.isNotBlank() },
            numEpisodes = media.numEpisodes ?: media.episodes.size,
        )
    }

    companion object {
        const val PAGE_SIZE = 200
        const val RECENT_LIMIT = 30
    }
}

/** Episode row from a server episode (expanded item, recent episodes, playlist entries). */
internal fun EpisodeDto.toEntity(serverId: String, itemId: String, recent: Boolean = false) = EpisodeEntity(
    serverId = serverId,
    itemId = itemId,
    id = id,
    title = title,
    subtitle = subtitle,
    description = description,
    season = season?.takeIf { it.isNotBlank() },
    episode = episode?.takeIf { it.isNotBlank() },
    publishedAt = publishedAt ?: 0,
    durationSec = duration ?: audioFile?.duration ?: 0.0,
    sizeBytes = size ?: audioFile?.metadata?.size ?: 0,
    recent = recent,
)
