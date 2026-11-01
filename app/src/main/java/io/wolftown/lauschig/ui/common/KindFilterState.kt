package io.wolftown.lauschig.ui.common

import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.library.FilterPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The kind filter of one screen resolved against the libraries: what can be chosen, what is chosen, which libraries pass. */
data class KindFilterState(val available: Set<LibraryKind>, val selected: Set<LibraryKind>, val libraryIds: Set<String>)

/** Libraries' effective kinds (podcasts hidden for now) combined with the filter stored for [screen]; an empty selection means all. */
fun kindFilterState(libraryDao: LibraryDao, prefs: FilterPreferences, serverId: String, screen: String): Flow<KindFilterState> =
    combine(libraryDao.observeAll(serverId), prefs.kinds(screen)) { libs, stored ->
        val available = libs.map { it.effectiveKind }.filter { it != LibraryKind.PODCAST }.toSet()
        val selected = stored.intersect(available)
        val effective = selected.ifEmpty { available }
        KindFilterState(available, selected, libs.filter { it.effectiveKind in effective }.map { it.id }.toSet())
    }.distinctUntilChanged()
