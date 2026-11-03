package io.wolftown.lauschig.links

import androidx.annotation.StringRes
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.auto.awaitOnline
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.LibrarySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject

/** Where a link leads in the app. */
sealed interface LinkTarget {
    data class Item(val itemId: String) : LinkTarget
    data class Series(val name: String) : LinkTarget
    data class Message(@StringRes val text: Int) : LinkTarget
}

/**
 * Opens an Audiobookshelf web link: only for the signed-in account's own server addresses. A title already in Room
 * opens at once (also offline); an unknown one is fetched first when the server is reachable.
 */
class LinkOpener(
    private val account: suspend () -> Account?,
    private val itemExists: suspend (serverId: String, itemId: String) -> Boolean,
    private val seriesName: suspend (serverId: String, seriesId: String) -> String?,
    /** True once the server is online, waiting a few seconds right after a cold start. */
    private val online: suspend () -> Boolean,
    private val loadDetails: suspend (serverId: String, itemId: String) -> Unit,
) {
    @Inject constructor(accounts: AccountStore, items: ItemDao, api: ApiProvider, sync: LibrarySync) : this(
        account = { accounts.account.first() },
        itemExists = { sid, id -> items.get(sid, id) != null },
        seriesName = items::seriesNameById,
        online = { awaitOnline(api.connection, ONLINE_WAIT_MS) },
        loadDetails = sync::loadDetails,
    )

    suspend fun open(url: String): LinkTarget {
        val a = account() ?: return LinkTarget.Message(R.string.link_no_server)
        val match = AbsLinks.parse(url, listOfNotNull(a.addresses.local, a.addresses.public))
        val link = when (match) {
            LinkMatch.ForeignServer -> return LinkTarget.Message(R.string.link_no_server)
            LinkMatch.Unsupported -> return LinkTarget.Message(R.string.link_unsupported)
            is LinkMatch.Ok -> match.link
        }
        return when (link) {
            is AbsLink.Item -> item(a.serverId, link.itemId)
            is AbsLink.Series -> seriesName(a.serverId, link.seriesId)?.let { LinkTarget.Series(it) } ?: LinkTarget.Message(R.string.link_series_unknown)
        }
    }

    private suspend fun item(serverId: String, itemId: String): LinkTarget {
        if (itemExists(serverId, itemId)) return LinkTarget.Item(itemId)
        if (!online()) return LinkTarget.Message(R.string.link_offline)
        return try {
            loadDetails(serverId, itemId)
            if (itemExists(serverId, itemId)) LinkTarget.Item(itemId) else LinkTarget.Message(R.string.link_failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            LinkTarget.Message(if (e.code() == 404) R.string.link_not_found else R.string.link_failed)
        } catch (e: Exception) {
            LinkTarget.Message(R.string.link_failed)
        }
    }

    companion object {
        const val ONLINE_WAIT_MS = 3_000L
    }
}
