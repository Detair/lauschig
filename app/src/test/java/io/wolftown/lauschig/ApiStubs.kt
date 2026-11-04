package io.wolftown.lauschig

import io.wolftown.lauschig.absapi.library.CollectionsPage
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.PlaylistsResponse
import io.wolftown.lauschig.absapi.me.BookmarkBody
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.BookmarksResponse
import io.wolftown.lauschig.absapi.me.ListeningSessionsPage
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.UserListeningSessionsPage

/** Test base: the bookmark and session calls fail unless a test overrides them. */
abstract class StubMeApi : MeApi {
    override suspend fun bookmarks(): BookmarksResponse = error("unused")
    override suspend fun createBookmark(itemId: String, body: BookmarkBody): BookmarkDto = error("unused")
    override suspend fun updateBookmark(itemId: String, body: BookmarkBody): BookmarkDto = error("unused")
    override suspend fun deleteBookmark(itemId: String, time: String): Unit = error("unused")
    override suspend fun itemListeningSessions(itemId: String, itemsPerPage: Int, page: Int): ListeningSessionsPage = error("unused")
    override suspend fun episodeListeningSessions(itemId: String, episodeId: String, itemsPerPage: Int, page: Int): ListeningSessionsPage = error("unused")
    override suspend fun listeningSessions(itemsPerPage: Int, page: Int): UserListeningSessionsPage = error("unused")
}

/** Test base: collections and playlists fail unless a test overrides them. */
abstract class StubLibraryApi : LibraryApi {
    override suspend fun collections(libraryId: String): CollectionsPage = error("unused")
    override suspend fun playlists(): PlaylistsResponse = error("unused")
}
