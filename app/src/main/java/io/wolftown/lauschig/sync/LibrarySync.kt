package io.wolftown.lauschig.sync

import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.ChapterDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.parseMinifiedSeries
import io.wolftown.lauschig.db.seriesSortKey
import io.wolftown.lauschig.net.ApiProvider
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
) {
    @Inject constructor(
        apiProvider: ApiProvider,
        libraries: LibraryDao,
        items: ItemDao,
        chapters: ChapterDao,
        audioFiles: AudioFileDao,
        progress: ProgressDao,
        downloads: DownloadDao,
    ) : this(apiProvider.libraryApi, libraries, items, chapters, audioFiles, progress, downloads)

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
        }
        failure?.let { throw it }
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
            val stale = mapped.filter { m -> before[m.id]?.let { it.seriesName != m.seriesName } == true }.map { it.id }
            if (stale.isNotEmpty()) items.invalidateDetails(serverId, stale)
            mapped.forEach { seen += it.id }
            page++
            // An empty page ends the loop even if the total was not reached; the count check below then blocks deletion.
        } while (result.results.isNotEmpty() && seen.size < total)

        if (seen.size != total) return
        val missing = before.keys - seen
        if (missing.isEmpty()) return
        val (keep, drop) = missing.partition { progress.get(serverId, it) != null || downloads.get(serverId, it) != null }
        if (keep.isNotEmpty()) items.setOrphaned(serverId, keep, true)
        if (drop.isNotEmpty()) items.deleteByIds(serverId, drop)
    }

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
        items.upsertDetails(entity)
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
            author = m.authorName,
            narrator = m.narratorName,
            seriesName = series?.first,
            seriesSequence = series?.second,
            seriesSortKey = seriesSortKey(series?.second),
            durationSec = media.duration ?: 0.0,
            coverUpdatedAt = updatedAt,
            genres = m.genres,
            tags = media.tags,
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val PAGE_SIZE = 200
    }
}
