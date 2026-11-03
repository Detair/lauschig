package io.wolftown.lauschig.local

import java.text.Normalizer

/** A document of a picked folder as the scanner sees it (see [DocumentTree]). */
data class DocEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val mimeType: String? = null,
)

/** A listed directory: its own entry, its path below the picked folder ("" for the folder itself) and its children. */
data class DirNode(val entry: DocEntry, val relativePath: String, val files: List<DocEntry>, val dirs: List<DirNode>)

/**
 * One local title found by [LocalGrouping.group]. [files] are in play order; [covers] are the image files of the title's
 * directory that may serve as its cover, best first. [singleFile]: the title is one `.m4b`.
 */
data class TitleGroup(
    val groupKey: String,
    val relativePath: String,
    val fallbackName: String,
    val files: List<DocEntry>,
    val covers: List<DocEntry>,
    val singleFile: Boolean,
)

/**
 * File names in natural order: digit runs compare by value (`2` < `10`, leading zeros ignored), text case-, accent- and
 * whitespace-insensitively (`Ärger` sorts with `A`). Names equal under those rules fall back to a plain comparison, so the
 * order is total and deterministic.
 */
object NaturalOrder : Comparator<String> {
    private val TOKEN = Regex("\\d+|\\D+")
    private val MARKS = Regex("\\p{M}+")
    private val SPACE = Regex("\\s+")

    /** Without accents, case and whitespace (`CD 2` and `CD10` compare by their numbers). */
    private fun fold(s: String) = SPACE.replace(MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), ""), "").lowercase()

    override fun compare(a: String, b: String): Int {
        val ta = TOKEN.findAll(a).map { it.value }.toList()
        val tb = TOKEN.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else {
                fold(x).compareTo(fold(y))
            }
            if (c != 0) return c
        }
        if (ta.size != tb.size) return ta.size.compareTo(tb.size)
        return a.compareTo(b)
    }
}

/**
 * Turns a listed folder tree into titles. Per directory: every `.m4b` is a title of its own; all other audio files
 * directly in it form one title (named after the directory). Disc folders (`CD 1`, `Disc2`, `Seite A`) are merged into
 * their parent's title. Hidden entries are skipped. Volume folders (`Band 1`) stay titles of their own.
 */
object LocalGrouping {
    private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mka")
    private val COVER_NAMES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png")
    private val DISC = Regex("""^(?:(?:cd|disc|disk)\s*[-_.]?\s*\d{1,3}|(?:seite|side)\s*[-_.]?\s*[ab12])$""", RegexOption.IGNORE_CASE)

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isAudio(e: DocEntry): Boolean = !e.isDirectory && !e.name.startsWith(".") && extension(e.name) in AUDIO

    private fun isBook(e: DocEntry) = extension(e.name) == "m4b"

    fun isDiscFolder(name: String): Boolean = DISC.matches(name.trim())

    fun baseName(name: String): String = name.substringBeforeLast('.', name).ifBlank { name }

    /** "Autor - Titel" (spaced dash) → author and title; anything else is a title only. */
    fun splitAuthorTitle(name: String): Pair<String?, String> {
        val i = name.indexOf(" - ")
        if (i <= 0) return null to name
        val author = name.substring(0, i).trim()
        val title = name.substring(i + 3).trim()
        return if (author.isEmpty() || title.isEmpty()) null to name else author to title
    }

    fun group(root: DirNode, rootName: String): List<TitleGroup> {
        val out = mutableListOf<TitleGroup>()
        visit(root, rootName, isRoot = true, out)
        return out
    }

    private fun covers(files: List<DocEntry>): List<DocEntry> =
        COVER_NAMES.mapNotNull { n -> files.firstOrNull { !it.isDirectory && it.name.equals(n, ignoreCase = true) } }

    private fun visit(dir: DirNode, rootName: String, isRoot: Boolean, out: MutableList<TitleGroup>) {
        val visibleDirs = dir.dirs.filter { !it.entry.name.startsWith(".") }
        val (discs, others) = visibleDirs.partition { isDiscFolder(it.entry.name) }
        val sortedDiscs = discs.sortedWith(compareBy(NaturalOrder) { it.entry.name })
        val audio = dir.files.filter(::isAudio)
        val books = audio.filter(::isBook).sortedWith(compareBy(NaturalOrder) { it.name })
        val rest = audio.filterNot(::isBook).sortedWith(compareBy(NaturalOrder) { it.name }) +
            sortedDiscs.flatMap { d -> d.files.filter { isAudio(it) && !isBook(it) }.sortedWith(compareBy(NaturalOrder) { it.name }) }
        val dirName = if (isRoot) rootName else dir.entry.name
        val dirCovers = covers(dir.files) + sortedDiscs.flatMap { covers(it.files) }
        val titlesHere = books.size + if (rest.isEmpty()) 0 else 1
        for (b in books) {
            out += TitleGroup(
                groupKey = "file:${b.documentId}",
                relativePath = if (dir.relativePath.isEmpty()) b.name else "${dir.relativePath}/${b.name}",
                fallbackName = if (titlesHere == 1 && !isRoot) dirName else baseName(b.name),
                files = listOf(b),
                covers = dirCovers,
                singleFile = true,
            )
        }
        if (rest.isNotEmpty()) {
            out += TitleGroup("dir:${dir.entry.documentId}", dir.relativePath, dirName, rest, dirCovers, singleFile = false)
        }
        // Disc folders' own subfolders and m4b files are titles of their own, like everywhere else.
        for (d in sortedDiscs) {
            val inner = d.copy(files = d.files.filter(::isBook))
            visitChildrenOnly(inner, rootName, out)
        }
        for (d in others.sortedWith(compareBy(NaturalOrder) { it.entry.name })) visit(d, rootName, isRoot = false, out)
    }

    private fun visitChildrenOnly(disc: DirNode, rootName: String, out: MutableList<TitleGroup>) {
        if (disc.files.isNotEmpty() || disc.dirs.isNotEmpty()) visit(disc, rootName, isRoot = false, out)
    }
}
