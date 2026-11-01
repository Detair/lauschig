package io.wolftown.lauschig.ui.podcast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.nav.ItemDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ItemKindViewModelTest {
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun lib(id: String, type: String) = LibraryEntity(sid, id, id, type, 0)

    @Test fun isPodcastItem_rules() {
        val item = ItemEntity(serverId = sid, id = "i", libraryId = "l", title = "T")
        assertTrue(isPodcastItem(item, lib("l", "podcast")))
        assertTrue(isPodcastItem(item.copy(numEpisodes = 2), lib("l", "book")))
        assertTrue(isPodcastItem(item.copy(numEpisodes = 2), null))
        assertFalse(isPodcastItem(item, lib("l", "book")))
        assertFalse(isPodcastItem(item, null))
    }

    private suspend fun kindOf(id: String): Boolean? {
        val store = FakeAccountStore()
        store.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val vm = ItemKindViewModel(SavedStateHandle(mapOf("itemId" to id)), store, db.itemDao(), db.libraryDao())
        // Read through the same flow the screen collects.
        return withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { vm.isPodcast.first { it != null } } }
    }

    @Test fun viewModel_podcastLibrary_episodes_andBook() = runTest {
        db.libraryDao().upsertFromServer(listOf(lib("books", "book"), lib("pods", "podcast")))
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(serverId = sid, id = "p", libraryId = "pods", title = "P"),
                ItemEntity(serverId = sid, id = "e", libraryId = "books", title = "E", numEpisodes = 3),
                ItemEntity(serverId = sid, id = "b", libraryId = "books", title = "B"),
                ItemEntity(serverId = sid, id = "u", libraryId = "unknown", title = "U"),
            ),
        )
        assertEquals(true, kindOf("p"))
        assertEquals(true, kindOf("e"))
        assertEquals(false, kindOf("b"))
        assertEquals(false, kindOf("u"))   // library unknown: a book
        assertEquals(false, kindOf("missing")) // no item at all: book route shows "not found"
    }
}
