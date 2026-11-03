package io.wolftown.lauschig.auto

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.absapi.library.coverUrl
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AutoModule {
    private const val TAG = "CoverProvider"

    @Provides
    fun carLibrary(
        @ApplicationContext context: Context, items: ItemDao, libraries: LibraryDao, progress: ProgressDao, episodes: EpisodeDao,
        downloads: DownloadDao, catalogDao: CatalogDao, catalog: Catalog,
    ): CarLibrary = CarLibrary(items, libraries, progress, episodes, downloads, catalogDao, catalog, ResourceCarTexts(context.resources))

    @Provides
    fun carSearch(library: CarLibrary): CarSearch = CarSearch(library)

    @Provides
    @Singleton
    fun coverStore(@ApplicationContext context: Context, files: DownloadFiles, api: ApiProvider, accounts: AccountStore): CoverStore = CoverStore(
        localCover = { serverId, key -> try { files.playableCover(serverId, key) } catch (e: Exception) { null } },
        cacheDir = File(context.cacheDir, CoverStore.DIR),
        fetch = { serverId, itemId -> fetchCover(api, accounts, serverId, itemId) },
    )

    /**
     * The server cover through the signed-in account's authenticated client, only while that server is online right
     * now (the playback service keeps the connection state current while a car browses). Runs on a binder thread.
     */
    private fun fetchCover(api: ApiProvider, accounts: AccountStore, serverId: String, itemId: String): CoverStore.Fetch {
        val online = api.connectionState.value as? ConnectionState.Online ?: return CoverStore.Fetch.Unavailable
        if (runBlocking { accounts.account.first()?.serverId } != serverId) return CoverStore.Fetch.Unavailable
        val client = api.clientFor(online.baseUrl).newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        return client.newCall(Request.Builder().url(coverUrl(online.baseUrl, itemId)).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code != 404) Log.w(TAG, "cover http ${resp.code}")
                return CoverStore.Fetch.Missing
            }
            if (resp.body.contentLength() > CoverStore.MAX_BYTES) return CoverStore.Fetch.Missing
            val bytes = resp.body.byteStream().use { it.readNBytesCompat(CoverStore.MAX_BYTES + 1) }
            if (bytes.size > CoverStore.MAX_BYTES) CoverStore.Fetch.Missing else CoverStore.Fetch.Found(bytes)
        }
    }

    /** Reads at most [limit] bytes (InputStream.readNBytes needs API 33). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
