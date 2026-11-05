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
 * When this device first saw each title marked finished – on the device's own clock, so the automatic clean-up never
 * compares the server's clock with the device's ([AutoDeleteRules]). Per server: switching accounts on a family device
 * keeps every account's times. The first time a server is seen, a silent baseline is taken: the titles finished then
 * are [BEFORE_BASELINE] and are never deleted automatically ("Aufräumen" still offers them).
 */
interface FinishedSeenStore {
    /**
     * Takes the keys [finished] now on [serverId]: a new one is seen at [now] (or [BEFORE_BASELINE] while the server
     * has no baseline yet), one no longer finished is forgotten (it gets a new time when it is finished again). Other
     * servers' entries stay. Returns key → first seen.
     */
    suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long>

    /** Drops everything of [serverId] (its data was purged). */
    suspend fun forget(serverId: String)

    companion object {
        /** Finished before this device took the server's baseline: never counts. */
        const val BEFORE_BASELINE = -1L
    }
}

@Singleton
class DataStoreFinishedSeen @Inject constructor(@ApplicationContext context: Context) : FinishedSeenStore {
    private val store = context.finishedSeenDataStore

    override suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long> {
        val data = store.data.first()
        val all = data[SEEN].orEmpty()
        val mine = all.filter { it.startsWith("$serverId|") }.toSet()
        val old = decode(mine)
        val baseline = serverId in data[BASELINES].orEmpty()
        // Without a baseline (a new account here, or the first start of this version) everything finished now is old.
        val next = finished.associateWith { if (baseline) old[it] ?: now else FinishedSeenStore.BEFORE_BASELINE }
        val encoded = next.map { (k, t) -> "$serverId|$k|$t" }.toSet()
        if (encoded != mine || !baseline) {
            store.edit {
                it[SEEN] = it[SEEN].orEmpty().filterNot { e -> e.startsWith("$serverId|") }.toSet() + encoded
                it[BASELINES] = it[BASELINES].orEmpty() + serverId
            }
        }
        return next
    }

    override suspend fun forget(serverId: String) {
        store.edit {
            it[SEEN] = it[SEEN].orEmpty().filterNot { e -> e.startsWith("$serverId|") }.toSet()
            it[BASELINES] = it[BASELINES].orEmpty() - serverId
        }
    }

    /** `serverId|key|millis`; keys never contain `|` (ABS ids, episode keys `item~episode`). */
    private fun decode(entries: Set<String>): Map<String, Long> = entries.mapNotNull { e ->
        val parts = e.split('|')
        if (parts.size != 3) null else parts[2].toLongOrNull()?.let { parts[1] to it }
    }.toMap()

    private companion object {
        val SEEN = stringSetPreferencesKey("seen")
        val BASELINES = stringSetPreferencesKey("baselines")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FinishedSeenModule {
    @Binds abstract fun seen(impl: DataStoreFinishedSeen): FinishedSeenStore
}
