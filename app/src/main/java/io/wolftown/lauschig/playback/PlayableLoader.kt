package io.wolftown.lauschig.playback

import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.first

/**
 * Loads what the player needs for a [PlayableKey]: a book is its item row; an episode is a synthetic item
 * (id = key, title = episode title, author = podcast title) on top of the chapters, audio files, progress and
 * download stored under the key. Null if the item (for an episode: the podcast or the episode) is unknown.
 */
suspend fun loadPlayable(items: ItemDao, episodes: EpisodeDao, serverId: String, key: String): ItemWithDetails? {
    val parsed = PlayableKey.parse(key)
    val episodeId = parsed.episodeId ?: return items.observeItem(serverId, key).first()
    val podcast = items.get(serverId, parsed.libraryItemId) ?: return null
    val episode = episodes.get(serverId, parsed.libraryItemId, episodeId) ?: return null
    val synthetic = ItemEntity(
        serverId = serverId,
        id = key,
        libraryId = podcast.libraryId,
        title = episode.title,
        author = podcast.title,
        durationSec = episode.durationSec,
        // The episode shows its podcast's cover; its version keeps cover URIs fresh.
        coverUpdatedAt = podcast.coverUpdatedAt,
        detailsLoaded = true,
    )
    return ItemWithDetails(
        synthetic,
        items.observeChapters(serverId, key).first(),
        items.observeAudioFiles(serverId, key).first(),
        items.observeProgress(serverId, key).first(),
        items.observeDownload(serverId, key).first(),
    )
}
