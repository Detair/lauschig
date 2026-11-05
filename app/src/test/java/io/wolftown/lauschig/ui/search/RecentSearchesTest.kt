package io.wolftown.lauschig.ui.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recent searches belong to one account (Phase 7, Task 1.1). */
class RecentSearchesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("searches.preferences_pb") } }
    private val active = MutableStateFlow<String?>(a)
    private val searches by lazy { RecentSearches(store, active) }

    @After fun tearDown() = scope.cancel()

    @Test fun eachAccountSeesOnlyItsOwn_andTheyWaitForItsReturn() = runBlocking {
        searches.record("Die drei")
        active.value = b
        assertEquals(emptyList<String>(), searches.queries.first())
        searches.record("Bibi")
        assertEquals(listOf("Bibi"), searches.queries.first())
        active.value = a
        assertEquals(listOf("Die drei"), searches.queries.first())
    }

    @Test fun anOpenList_followsTheSwitch() = runBlocking {
        searches.record("Die drei")
        val seen = kotlinx.coroutines.channels.Channel<List<String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { searches.queries.collect { seen.send(it) } }
        try {
            assertEquals(listOf("Die drei"), withTimeout(5_000) { seen.receive() })
            active.value = b
            assertEquals(emptyList<String>(), withTimeout(5_000) { seen.receive() })
        } finally {
            job.cancel()
        }
    }

    @Test fun signedOut_nothingIsShown_nothingIsRecorded() = runBlocking {
        searches.record("Die drei")
        active.value = null
        assertEquals(emptyList<String>(), searches.queries.first())
        searches.record("niemand")
        active.value = a
        assertEquals(listOf("Die drei"), searches.queries.first())
    }

    @Test fun clear_goesOnlyForThatAccount() = runBlocking {
        searches.record("Die drei")
        active.value = b
        searches.record("Bibi")
        searches.clear(a)
        assertEquals(listOf("Bibi"), searches.queries.first())
        active.value = a
        assertEquals(emptyList<String>(), searches.queries.first())
    }

    /** What 0.4.0 stored: one list for the device. */
    private suspend fun oldVersionStored(list: String) = store.edit { it[stringPreferencesKey("queries")] = list }

    @Test fun upgrade_theOldListIsTheActiveAccounts() = runBlocking {
        oldVersionStored("Die drei\nBibi")
        searches.migrate(a)
        assertEquals(listOf("Die drei", "Bibi"), searches.queries.first())
        active.value = b
        assertEquals(emptyList<String>(), searches.queries.first())
    }

    @Test fun upgrade_signedOut_theOldListHasNoOwner_andGoes() = runBlocking {
        oldVersionStored("Die drei")
        searches.migrate(null)
        assertEquals(emptyList<String>(), searches.queries.first())
        searches.migrate(a)
        assertEquals(emptyList<String>(), searches.queries.first())
    }

    @Test fun upgrade_runsOnce_neverOverAnAccountsNewerList() = runBlocking {
        oldVersionStored("alt")
        searches.migrate(a)
        searches.record("neu")
        searches.migrate(a)
        searches.migrate(b)
        assertEquals(listOf("neu", "alt"), searches.queries.first())
        active.value = b
        assertEquals(emptyList<String>(), searches.queries.first())
    }
}
