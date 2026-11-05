package io.wolftown.lauschig.auto

import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.catalog.SeriesEntry
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.local.NaturalOrder
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.first

/** The few texts of the tree (string resources in the app, plain strings in tests). */
interface CarTexts {
    val continueListening: String
    val downloads: String
    val series: String
    val libraries: String
    val device: String
    /** Signed out: "In Lauschig auf dem Handy anmelden". */
    val signIn: String
    /** "noch 2 h 5 min" for [sec] seconds left. */
    fun remaining(sec: Double): String
    /** "Band 3". */
    fun volume(sequence: String): String
    /** "12 Titel". */
    fun volumes(count: Int): String
}

/** [account]: the signed-in server (null: only device titles); [online]: that server is reachable right now. */
data class CarContext(val account: String?, val online: Boolean)

/** One row of the car's tree; [CarMediaItems] turns it into a Media3 item. */
data class CarEntry(
    val node: CarNode,
    val title: String,
    val type: Type,
    val subtitle: String? = null,
    val cover: CoverRef? = null,
    /** How a folder's children are shown. */
    val childStyle: Style = Style.LIST,
    /** Heard fraction 0..1 (0: not started). */
    val progress: Float = 0f,
    val finished: Boolean = false,
    /** Plays without the server (complete download or device title). */
    val downloaded: Boolean = false,
) {
    /** [HINT]: a line of text (signed out), neither playable nor browsable. */
    enum class Type { FOLDER, BOOK, EPISODE, PODCAST, SERIES, LIBRARY, HINT }
    enum class Style { LIST, GRID }

    val id: String get() = MediaIds.of(node)
    val playable: Boolean get() = type == Type.BOOK || type == Type.EPISODE
    val browsable: Boolean get() = !playable && type != Type.HINT
}

/**
 * The browse tree for Android Auto and other media browsers, straight from Room (offline first: nothing here touches
 * the network). Offline, lists contain only titles that play without the server; folders stay. Signed out, only the
 * device titles exist. Lists are complete; the caller pages them.
 */
class CarLibrary(
    private val items: ItemDao,
    private val libraries: LibraryDao,
    private val progress: ProgressDao,
    private val episodes: EpisodeDao,
    private val downloads: DownloadDao,
    private val catalogDao: CatalogDao,
    private val catalog: Catalog,
    private val texts: CarTexts,
) {
    private val dev = DeviceLibrary.SERVER_ID

    /**
     * Children of [node]; null if [node] is unknown here or not a folder. Signed out the root holds only the sign-in hint
     * and every folder is empty: nothing plays from the car without an account on the phone (device titles neither).
     */
    suspend fun children(node: CarNode, ctx: CarContext): List<CarEntry>? = if (ctx.account == null) signedOutChildren(node) else when (node) {
        CarNode.Root -> rootEntries()
        CarNode.Continue -> continueListening(ctx)
        CarNode.Downloads -> downloaded(ctx)
        CarNode.SeriesList -> seriesList(ctx)
        is CarNode.Series -> volumes(node.name, ctx)
        CarNode.Libraries -> libraryList(ctx)
        is CarNode.Library -> libraryTitles(node.libraryId, ctx)
        is CarNode.Podcast -> podcastEpisodes(node.itemId, ctx)
        CarNode.Recent, CarNode.SignIn, is CarNode.Title -> null
    }

    private fun signedOutChildren(node: CarNode): List<CarEntry>? = when (node) {
        CarNode.Root -> listOf(signInHint())
        CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries, is CarNode.Series, is CarNode.Library, is CarNode.Podcast -> emptyList()
        CarNode.Recent, CarNode.SignIn, is CarNode.Title -> null
    }

    private fun signInHint() = CarEntry(CarNode.SignIn, texts.signIn, CarEntry.Type.HINT)

    /** The entry of [node] itself; null if unknown (or a title of another account, or anything but the hint signed out). */
    suspend fun item(node: CarNode, ctx: CarContext): CarEntry? = if (ctx.account == null) {
        when (node) {
            CarNode.Root -> CarEntry(CarNode.Root, "", CarEntry.Type.FOLDER)
            CarNode.SignIn -> signInHint()
            else -> null
        }
    } else when (node) {
        CarNode.Root -> CarEntry(CarNode.Root, "", CarEntry.Type.FOLDER)
        CarNode.SignIn -> null
        CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries -> rootEntries().first { it.node == node }
        is CarNode.Series -> snapshot(ctx).let { snap -> seriesFacets(snap).firstOrNull { same(it.name, node.name) }?.let { seriesEntry(it, snap) } }
        is CarNode.Library -> libraryList(ctx).firstOrNull { it.node == node }
        is CarNode.Podcast -> ctx.account?.let { sid -> items.get(sid, node.itemId)?.let { podcastEntry(sid, it) } }
        is CarNode.Title -> title(node, ctx)
        CarNode.Recent -> null
    }

    fun rootEntries(): List<CarEntry> = listOf(
        CarEntry(CarNode.Continue, texts.continueListening, CarEntry.Type.FOLDER),
        CarEntry(CarNode.Downloads, texts.downloads, CarEntry.Type.FOLDER),
        CarEntry(CarNode.SeriesList, texts.series, CarEntry.Type.FOLDER, childStyle = CarEntry.Style.GRID),
        CarEntry(CarNode.Libraries, texts.libraries, CarEntry.Type.FOLDER),
    )

    /**
     * What search and the series pages work on: the account's titles outside podcast libraries plus the device
     * titles, with progress and the download state.
     */
    class Snapshot(
        val ctx: CarContext,
        val titles: List<ItemEntity>,
        private val progress: Map<String, ProgressEntity>,
        private val downloaded: Set<String>,
    ) {
        fun progressOf(item: ItemEntity): ProgressEntity? = progress[key(item.serverId, item.id)]
        fun isDownloaded(item: ItemEntity): Boolean = DeviceLibrary.isLocal(item.serverId) || item.id in downloaded

        /** Online everything but orphaned streams plays; offline only what is on the device. */
        fun isPlayable(item: ItemEntity): Boolean = isDownloaded(item) || (ctx.online && !item.orphaned)

        val playableTitles: List<ItemEntity> by lazy { titles.filter(::isPlayable) }

        companion object {
            fun key(serverId: String, id: String) = "$serverId\u0000$id"
        }
    }

    suspend fun snapshot(ctx: CarContext): Snapshot {
        val sid = ctx.account
        val device = items.getByLibrary(dev, DeviceLibrary.LIBRARY_ID)
        val deviceProgress = progress.all(dev)
        if (sid == null) {
            return Snapshot(ctx, device, deviceProgress.associateBy { Snapshot.key(dev, it.itemId) }, emptySet())
        }
        val podcastLibs = podcastLibraries(libraries.getAll(sid))
        val account = catalogDao.observeAll(sid).first().filter { it.libraryId !in podcastLibs }
        val byKey = HashMap<String, ProgressEntity>()
        progress.all(sid).forEach { byKey[Snapshot.key(sid, it.itemId)] = it }
        deviceProgress.forEach { byKey[Snapshot.key(dev, it.itemId)] = it }
        return Snapshot(ctx, account + device, byKey, items.observeDownloaded(sid).first().mapTo(HashSet()) { it.id })
    }

    /** Every series of the playable account titles as folder entries (search hits). */
    fun seriesEntries(snap: Snapshot): List<CarEntry> = seriesFacets(snap).map { seriesEntry(it, snap) }

    /** A playable entry for [item] (book or device title) as search and the lists show it. */
    fun titleEntry(item: ItemEntity, snap: Snapshot, subtitle: String? = null): CarEntry =
        bookEntry(item, snap.progressOf(item), snap.isDownloaded(item), subtitle)

    // ---- Weiterhören ----

    private suspend fun continueListening(ctx: CarContext): List<CarEntry> {
        val rows = ArrayList<Pair<Long, CarEntry>>()
        val deviceProgress = progress.all(dev).associateBy { it.itemId }
        items.observeInProgress(dev).first().forEach { item ->
            val p = deviceProgress[item.id]
            if (p != null && !p.hideFromContinue) rows += p.lastUpdate to bookEntry(item, p, downloaded = true)
        }
        val sid = ctx.account
        if (sid != null) {
            val byId = progress.all(sid).associateBy { it.itemId }
            val downloaded = items.observeDownloaded(sid).first().mapTo(HashSet()) { it.id }
            items.observeInProgress(sid).first().forEach { item ->
                val p = byId[item.id] ?: return@forEach
                val local = item.id in downloaded
                if (!p.hideFromContinue && (local || ctx.online && !item.orphaned)) rows += p.lastUpdate to bookEntry(item, p, local)
            }
            val complete = downloads.observeCompleteIds(sid).first().toHashSet()
            val podcasts = HashMap<String, ItemEntity?>()
            episodes.observeStarted(sid, CONTINUE_LIMIT).first().forEach { e ->
                val key = PlayableKey.episode(e.itemId, e.id)
                val podcast = podcasts.getOrPut(e.itemId) { items.get(sid, e.itemId) } ?: return@forEach
                val p = byId[key] ?: return@forEach
                val local = key in complete
                if (local || ctx.online) rows += p.lastUpdate to episodeEntry(sid, podcast, e, p, local)
            }
        }
        return rows.sortedByDescending { it.first }.take(CONTINUE_LIMIT).map { it.second }
    }

    // ---- Heruntergeladen ----

    private suspend fun downloaded(ctx: CarContext): List<CarEntry> {
        val sid = ctx.account ?: return emptyList()
        val byId = progress.all(sid).associateBy { it.itemId }
        return items.observeDownloaded(sid).first().sortedWith(byTitle).map { bookEntry(it, byId[it.id], downloaded = true) }
    }

    // ---- Serien ----

    private fun seriesFacets(snap: Snapshot): List<Facet> = catalog.facets(snap.playableTitles.filter { !DeviceLibrary.isLocal(it.serverId) }).series

    private fun seriesEntry(facet: Facet, snap: Snapshot): CarEntry {
        val cover = facet.coverItemId?.let { id -> snap.titles.firstOrNull { it.id == id && !DeviceLibrary.isLocal(it.serverId) } }
        return CarEntry(
            CarNode.Series(facet.name), facet.name, CarEntry.Type.SERIES,
            subtitle = texts.volumes(facet.count),
            cover = cover?.let { CoverRef(it.serverId, it.id, it.coverUpdatedAt) },
        )
    }

    private suspend fun seriesList(ctx: CarContext): List<CarEntry> {
        if (ctx.account == null) return emptyList()
        val snap = snapshot(ctx)
        val facets = seriesFacets(snap)
        // Series heard recently first (most recent progress of any volume), the rest by name.
        val activity = HashMap<String, Long>()
        snap.playableTitles.forEach { item ->
            val name = item.seriesName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val at = snap.progressOf(item)?.lastUpdate ?: return@forEach
            if (at > (activity[name] ?: 0L)) activity[name] = at
        }
        return facets.sortedWith(
            compareByDescending<Facet> { activity[it.name.trim().lowercase()] ?: 0L }.thenBy(NaturalOrder) { it.name },
        ).map { seriesEntry(it, snap) }
    }

    private suspend fun volumes(name: String, ctx: CarContext): List<CarEntry>? {
        if (ctx.account == null) return null
        val snap = snapshot(ctx)
        val account = snap.playableTitles.filter { !DeviceLibrary.isLocal(it.serverId) }
        return catalog.bySeries(account, name).filterIsInstance<SeriesEntry.Volume>().map { v ->
            val item = v.item
            val p = snap.progressOf(item)
            val volume = item.seriesSequence?.takeIf { it.isNotBlank() }?.let(texts::volume)
            val subtitle = when {
                volume == null -> null
                inProgress(p) -> "$volume · ${texts.remaining(remainingSec(item, p!!))}"
                else -> volume
            }
            bookEntry(item, p, snap.isDownloaded(item), subtitle)
        }
    }

    // ---- Bibliotheken ----

    private suspend fun libraryList(ctx: CarContext): List<CarEntry> {
        val out = ArrayList<CarEntry>()
        ctx.account?.let { sid ->
            libraries.getAll(sid).forEach { lib ->
                out += CarEntry(CarNode.Library(lib.id), lib.name, CarEntry.Type.LIBRARY, childStyle = CarEntry.Style.GRID)
            }
        }
        if (items.idsInLibrary(dev, DeviceLibrary.LIBRARY_ID).isNotEmpty()) {
            out += CarEntry(CarNode.Library(DeviceLibrary.LIBRARY_ID), texts.device, CarEntry.Type.LIBRARY, childStyle = CarEntry.Style.GRID)
        }
        return out
    }

    private suspend fun libraryTitles(libraryId: String, ctx: CarContext): List<CarEntry>? {
        if (libraryId == DeviceLibrary.LIBRARY_ID) {
            val byId = progress.all(dev).associateBy { it.itemId }
            return items.getByLibrary(dev, DeviceLibrary.LIBRARY_ID).sortedWith(byTitle).map { bookEntry(it, byId[it.id], downloaded = true) }
        }
        val sid = ctx.account ?: return null
        val lib = libraries.getAll(sid).firstOrNull { it.id == libraryId } ?: return null
        val list = items.getByLibrary(sid, libraryId).sortedWith(byTitle)
        if (lib.effectiveKind == LibraryKind.PODCAST) {
            val complete = downloads.observeCompleteIds(sid).first()
            val withDownloads = complete.mapNotNullTo(HashSet()) { k -> PlayableKey.parse(k).takeIf { it.episodeId != null }?.libraryItemId }
            return list.filter { ctx.online || it.id in withDownloads }.map { podcastEntry(sid, it) }
        }
        val byId = progress.all(sid).associateBy { it.itemId }
        val downloaded = items.observeDownloaded(sid).first().mapTo(HashSet()) { it.id }
        return list.filter { it.id in downloaded || ctx.online && !it.orphaned }.map { bookEntry(it, byId[it.id], it.id in downloaded) }
    }

    private suspend fun podcastEpisodes(itemId: String, ctx: CarContext): List<CarEntry>? {
        val sid = ctx.account ?: return null
        val podcast = items.get(sid, itemId) ?: return null
        val complete = downloads.observeCompleteIds(sid).first().toHashSet()
        val byId = progress.all(sid).associateBy { it.itemId }
        return episodes.observeForItem(sid, itemId).first().mapNotNull { e ->
            val key = PlayableKey.episode(itemId, e.id)
            val local = key in complete
            if (!local && !ctx.online) null else episodeEntry(sid, podcast, e, byId[key], local)
        }
    }

    // ---- single titles ----

    private suspend fun title(node: CarNode.Title, ctx: CarContext): CarEntry? {
        val sid = node.serverId
        if (!DeviceLibrary.isLocal(sid) && sid != ctx.account) return null
        val parsed = PlayableKey.parse(node.key)
        val local = items.observeDownload(sid, node.key).first()?.state == DownloadState.COMPLETE
        val p = progress.get(sid, node.key)
        val episodeId = parsed.episodeId
        if (episodeId != null) {
            val podcast = items.get(sid, parsed.libraryItemId) ?: return null
            val e = episodes.get(sid, parsed.libraryItemId, episodeId) ?: return null
            return episodeEntry(sid, podcast, e, p, local)
        }
        val item = items.get(sid, node.key) ?: return null
        return bookEntry(item, p, local || DeviceLibrary.isLocal(sid))
    }

    // ---- entries ----

    private fun bookEntry(item: ItemEntity, p: ProgressEntity?, downloaded: Boolean, subtitle: String? = null): CarEntry = CarEntry(
        CarNode.Title(item.serverId, item.id), item.title, CarEntry.Type.BOOK,
        subtitle = subtitle ?: if (inProgress(p)) texts.remaining(remainingSec(item, p!!)) else item.author,
        cover = CoverRef(item.serverId, item.id, item.coverUpdatedAt),
        progress = fraction(p, item.durationSec),
        finished = p?.isFinished == true,
        downloaded = downloaded,
    )

    private fun episodeEntry(sid: String, podcast: ItemEntity, e: EpisodeEntity, p: ProgressEntity?, downloaded: Boolean): CarEntry {
        val key = PlayableKey.episode(podcast.id, e.id)
        val duration = p?.durationSec?.takeIf { it > 0 } ?: e.durationSec
        return CarEntry(
            CarNode.Title(sid, key), e.title, CarEntry.Type.EPISODE,
            subtitle = if (inProgress(p)) texts.remaining((duration - p!!.currentTimeSec).coerceAtLeast(0.0)) else podcast.title,
            cover = CoverRef(sid, key, podcast.coverUpdatedAt),
            progress = fraction(p, e.durationSec),
            finished = p?.isFinished == true,
            downloaded = downloaded,
        )
    }

    private fun podcastEntry(sid: String, podcast: ItemEntity) = CarEntry(
        CarNode.Podcast(podcast.id), podcast.title, CarEntry.Type.PODCAST,
        subtitle = podcast.author,
        cover = CoverRef(sid, podcast.id, podcast.coverUpdatedAt),
    )

    private fun inProgress(p: ProgressEntity?) = p != null && !p.isFinished && p.currentTimeSec > 0

    private fun remainingSec(item: ItemEntity, p: ProgressEntity): Double =
        ((p.durationSec.takeIf { it > 0 } ?: item.durationSec) - p.currentTimeSec).coerceAtLeast(0.0)

    private fun fraction(p: ProgressEntity?, itemDuration: Double): Float {
        if (p == null) return 0f
        if (p.isFinished) return 1f
        val duration = p.durationSec.takeIf { it > 0 } ?: itemDuration
        return if (duration > 0) (p.currentTimeSec / duration).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    private fun podcastLibraries(libs: List<LibraryEntity>) = libs.filter { it.effectiveKind == LibraryKind.PODCAST }.mapTo(HashSet()) { it.id }

    private companion object {
        const val CONTINUE_LIMIT = 50
        val byTitle: Comparator<ItemEntity> = compareBy(NaturalOrder) { it.title }
    }
}
