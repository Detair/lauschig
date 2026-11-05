package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.finishedSeenDataStore by preferencesDataStore(name = "finished_seen")

/**
 * When this device first saw each title of the signed-in account marked finished – on the device's own clock, so the
 * automatic clean-up never compares the server's clock with the device's ([AutoDeleteRules]).
 */
interface FinishedSeenStore {
    /**
     * Takes the keys [finished] now on [serverId]: a new one is seen at [now], one no longer finished is forgotten (it
     * gets a new time when it is finished again), entries of other servers go. Returns key → first seen.
     */
    suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long>
}

@Singleton
class DataStoreFinishedSeen @Inject constructor(@ApplicationContext context: Context) : FinishedSeenStore {
    private val store = context.finishedSeenDataStore

    override suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long> {
        val old = decode(store.data.first()[SEEN].orEmpty(), serverId)
        val next = finished.associateWith { old[it] ?: now }
        if (next != old || store.data.first()[SEEN].orEmpty().any { !it.startsWith("$serverId|") }) {
            store.edit { it[SEEN] = next.map { (k, t) -> "$serverId|$k|$t" }.toSet() }
        }
        return next
    }

    private fun decode(entries: Set<String>, serverId: String): Map<String, Long> = entries.mapNotNull { e ->
        val parts = e.split('|')
        if (parts.size != 3 || parts[0] != serverId) null else parts[2].toLongOrNull()?.let { parts[1] to it }
    }.toMap()

    private companion object {
        val SEEN = stringSetPreferencesKey("seen")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FinishedSeenModule {
    @Binds abstract fun seen(impl: DataStoreFinishedSeen): FinishedSeenStore
}
