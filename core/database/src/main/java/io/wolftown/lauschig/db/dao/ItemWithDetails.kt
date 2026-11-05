package io.wolftown.lauschig.db.dao

import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity

/** Assembled by [ItemDao.observeItem]; Room's @Relation cannot join on the composite (serverId, id) key. */
data class ItemWithDetails(
    val item: ItemEntity,
    val chapters: List<ChapterEntity>,
    val audioFiles: List<AudioFileEntity>,
    val progress: ProgressEntity?,
    val download: DownloadEntity?,
)
