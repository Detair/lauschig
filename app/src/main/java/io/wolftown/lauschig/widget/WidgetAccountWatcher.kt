package io.wolftown.lauschig.widget

import android.content.Context
import io.wolftown.lauschig.diagnostics.Log
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Sign-out or another server: the widget forgets the old account's title (a device title stays). A switch to another
 * profile also takes a device title: that was the left account's last title, the next one has its own. Runs for the app's
 * lifetime from [io.wolftown.lauschig.LauschigApp]; the widget also hides a foreign title on its own (see
 * [WidgetMapping.forDisplay]), this clears the store so it does not come back. Another account also redraws the widget
 * when nothing was stored: what it shows without a stored title is that account's last title ([WidgetSource]).
 */
class WidgetAccountWatcher(
    private val accounts: AccountStore,
    private val store: WidgetStore,
    private val update: suspend () -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, accounts: AccountStore, store: WidgetStore) :
        this(accounts, store, { LauschigWidget().updateAll(context) })

    suspend fun run() {
        var started = false
        var previous: String? = null
        accounts.account.map { it?.serverId }.distinctUntilChanged().collect { sid ->
            val changed = started
            val switched = changed && previous != null && sid != null
            started = true
            previous = sid
            try {
                val cleared = if (switched) clearUnlessOf(store, sid) else clearIfForeign(store, sid)
                if (cleared || changed) update()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WidgetAccountWatcher", "widget cleanup failed", e)
            }
        }
    }

    companion object {
        /** Clears the stored title if it does not belong to [accountServerId]; true if it did. */
        suspend fun clearIfForeign(store: WidgetStore, accountServerId: String?): Boolean {
            val stored = store.get() ?: return false
            if (WidgetMapping.belongsTo(stored, accountServerId)) return false
            store.set(WidgetState())
            return true
        }

        /** Clears the stored title, a device title too, unless it is [serverId]'s; true if it did. */
        suspend fun clearUnlessOf(store: WidgetStore, serverId: String?): Boolean {
            val stored = store.get() ?: return false
            if (stored.serverId == serverId) return false
            store.set(WidgetState())
            return true
        }
    }
}
