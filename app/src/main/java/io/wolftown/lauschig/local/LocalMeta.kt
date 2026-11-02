package io.wolftown.lauschig.local

/** What [AudioProbe] read from one file; every field may be missing. */
data class AudioTags(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val author: String? = null,
    val composer: String? = null,
    val durationMs: Long? = null,
)

/** A file of a title with a readable duration. */
data class PlayableFile(val entry: DocEntry, val durationSec: Double, val tags: AudioTags)

/** A chapter on the title's timeline (seconds). */
data class LocalChapter(val title: String, val startSec: Double, val endSec: Double)

data class LocalTitleMeta(
    val title: String,
    val author: String?,
    val narrator: String?,
    val files: List<PlayableFile>,
    val durationSec: Double,
    val chapters: List<LocalChapter>,
)

/**
 * Metadata of a local title from its files' tags, with fallbacks (rules in the phase-3C plan): the title from the title
 * tag (one file) or the album (several files; a file's title is a chapter name there), else the folder/file name, where
 * `Autor - Titel` also gives the author. Author: album artist, artist, author tag. Narrator: composer (as in ABS).
 * Files without a readable duration are left out; several files become one chapter each.
 */
object LocalMeta {
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** [tags] belong to [TitleGroup.files] by position; null = the file could not be read. Null if nothing is playable. */
    fun build(group: TitleGroup, tags: List<AudioTags?>): LocalTitleMeta? {
        val files = group.files.mapIndexedNotNull { i, e ->
            val t = tags.getOrNull(i) ?: return@mapIndexedNotNull null
            val ms = t.durationMs ?: return@mapIndexedNotNull null
            if (ms <= 0) null else PlayableFile(e, ms / 1000.0, t)
        }
        if (files.isEmpty()) return null
        val first = files.first().tags
        val (folderAuthor, folderTitle) = LocalGrouping.splitAuthorTitle(group.fallbackName)
        val tagTitle = if (group.files.size == 1) first.title.clean() ?: first.album.clean() else first.album.clean()
        val title = tagTitle ?: folderTitle
        val author = first.albumArtist.clean() ?: first.artist.clean() ?: first.author.clean() ?: folderAuthor?.takeIf { tagTitle == null }
        return LocalTitleMeta(
            title = title,
            author = author,
            narrator = first.composer.clean(),
            files = files,
            durationSec = files.sumOf { it.durationSec },
            chapters = chapters(files),
        )
    }

    private fun chapters(files: List<PlayableFile>): List<LocalChapter> {
        if (files.size < 2) return emptyList()
        val titles = files.map { it.tags.title.clean() }
        val useTags = titles.all { it != null } && titles.toSet().size == titles.size
        var start = 0.0
        return files.mapIndexed { i, f ->
            val name = if (useTags) titles[i]!! else LocalGrouping.baseName(f.entry.name)
            LocalChapter(name, start, start + f.durationSec).also { start += f.durationSec }
        }
    }
}
