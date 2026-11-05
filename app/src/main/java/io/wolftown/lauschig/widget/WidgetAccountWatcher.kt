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
 * Sign-out or another server: the widget forgets the old account's title (a device title stays). Runs for the app's
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
        accounts.account.map { it?.serverId }.distinctUntilChanged().collect { sid ->
            val changed = started
            started = true
            try {
                if (clearIfForeign(store, sid) || changed) update()
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
    }
}
