package io.wolftown.lauschig.links

import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class LinkOpenerTest {
    private val sid = "0123456789abcdef"
    private val account = Account(sid, "u1", "fabian", false, ServerAddresses(null, "https://abs.example.org/".toHttpUrl()), emptySet())
    private val inRoom = mutableSetOf("known")
    private var online = true
    private val loaded = mutableListOf<String>()
    private var loadFails: Exception? = null

    private fun opener(a: Account? = account) = LinkOpener(
        account = { a },
        itemExists = { s, id -> s == sid && id in inRoom },
        seriesName = { _, id -> if (id == "ser1") "Die drei ???" else null },
        online = { online },
        loadDetails = { _, id -> loaded += id; loadFails?.let { throw it }; inRoom += id },
    )

    @Test fun knownTitle_opensAtOnce_alsoOffline() = runTest {
        online = false
        assertEquals(LinkTarget.Item("known"), opener().open("https://abs.example.org/item/known"))
        assertEquals(emptyList<String>(), loaded)
    }

    @Test fun unknownTitle_isFetchedWhenOnline() = runTest {
        assertEquals(LinkTarget.Item("new"), opener().open("https://abs.example.org/item/new"))
        assertEquals(listOf("new"), loaded)
    }

    @Test fun unknownTitle_offline_isNotAvailable() = runTest {
        online = false
        assertEquals(LinkTarget.Message(R.string.link_offline), opener().open("https://abs.example.org/item/new"))
    }

    @Test fun unknownTitle_404_isNotFound_otherErrorsFail() = runTest {
        loadFails = HttpException(Response.error<Any>(404, "".toResponseBody()))
        assertEquals(LinkTarget.Message(R.string.link_not_found), opener().open("https://abs.example.org/item/gone"))
        loadFails = java.io.IOException("reset")
        assertEquals(LinkTarget.Message(R.string.link_failed), opener().open("https://abs.example.org/item/gone"))
    }

    @Test fun foreignServer_orNoAccount() = runTest {
        assertEquals(LinkTarget.Message(R.string.link_no_server), opener().open("https://other.example/item/known"))
        assertEquals(LinkTarget.Message(R.string.link_no_server), opener(null).open("https://abs.example.org/item/known"))
    }

    @Test fun series_byItsStoredName() = runTest {
        assertEquals(LinkTarget.Series("Die drei ???"), opener().open("https://abs.example.org/library/l1/series/ser1"))
        assertEquals(LinkTarget.Message(R.string.link_series_unknown), opener().open("https://abs.example.org/library/l1/series/other"))
        assertEquals(LinkTarget.Message(R.string.link_unsupported), opener().open("https://abs.example.org/config"))
    }
}
