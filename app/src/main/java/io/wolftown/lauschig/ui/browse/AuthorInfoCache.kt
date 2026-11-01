package io.wolftown.lauschig.ui.browse

import io.wolftown.lauschig.absapi.library.AuthorDto
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional author images and bios from `GET api/libraries/{id}/authors`, loaded at most once per library and app run
 * while online and kept in memory. Failures (or no connection) just leave the cache empty: the UI never depends on it.
 */
@Singleton
class AuthorInfoCache(
    private val apis: Flow<LibraryApi?>,
    private val libraryIds: suspend (serverId: String) -> List<String>,
) {
    @Inject constructor(api: ApiProvider, libraries: LibraryDao) :
        this(api.libraryApi, { sid -> libraries.observeAll(sid).first().map { it.id } })

    private val byKey = MutableStateFlow<Map<String, AuthorDto>>(emptyMap())
    private val started = ConcurrentHashMap.newKeySet<String>()

    /** All known authors, keyed by [key]. */
    val authors: StateFlow<Map<String, AuthorDto>> get() = byKey

    /** Waits for a connection, then loads every library of [serverId] that is not loaded yet. Never throws (except cancellation). */
    suspend fun ensureLoaded(serverId: String) {
        try {
            val ids = libraryIds(serverId).filter { "$serverId/$it" !in started }
            if (ids.isEmpty()) return
            val api = apis.filterNotNull().first()
            for (id in ids) {
                val marker = "$serverId/$id"
                if (!started.add(marker)) continue
                try {
                    val loaded = api.authors(id).authors.associateBy { key(serverId, it.name) }
                    byKey.update { it + loaded }
                } catch (e: CancellationException) {
                    started.remove(marker)
                    throw e
                } catch (e: Exception) {
                    started.remove(marker) // a later visit may retry
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Optional extra: swallow.
        }
    }

    companion object {
        /** Case-insensitive lookup key. */
        fun key(serverId: String, name: String) = "$serverId/${name.trim().lowercase()}"
    }
}
