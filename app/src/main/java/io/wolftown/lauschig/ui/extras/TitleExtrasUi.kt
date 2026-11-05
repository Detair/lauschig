package io.wolftown.lauschig.ui.extras

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.history.History
import io.wolftown.lauschig.history.HistoryEntry
import io.wolftown.lauschig.ui.detail.formatClock
import java.text.DateFormat
import java.util.Date

/** "1 h 5 min", "12 min", "40 s". */
@Composable
fun listenedText(sec: Double): String {
    val total = sec.coerceAtLeast(0.0).toLong()
    val h = (total / 3600).toInt()
    val m = (total % 3600 / 60).toInt()
    return when {
        h > 0 -> stringResource(R.string.history_duration_h_m, h, m)
        m > 0 -> stringResource(R.string.history_duration_m, m)
        else -> stringResource(R.string.history_duration_s, total.toInt())
    }
}

/** Note editor for a new or existing bookmark; an empty note is fine. */
@Composable
fun BookmarkNoteDialog(title: String, initial: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.bookmark_note_label)) },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("bookmarkNote"),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { null }) }, modifier = Modifier.testTag("bookmarkSave")) { Text(stringResource(R.string.bookmark_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmark_cancel)) } },
    )
}

/** Bookmark rows: tap jumps there, pencil edits the note, bin deletes. */
fun LazyListScope.bookmarkItems(
    rows: List<BookmarkEntity>,
    onJump: (BookmarkEntity) -> Unit,
    onEdit: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
) {
    items(rows, key = { "bm:${it.itemKey}:${it.timeSec}" }, contentType = { "bookmark" }) { b ->
        ListItem(
            headlineContent = { Text(formatClock(b.timeSec)) },
            supportingContent = b.note?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
            leadingContent = { Icon(Icons.Rounded.Bookmark, contentDescription = null) },
            trailingContent = {
                Row {
                    IconButton(onClick = { onEdit(b) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.bookmark_edit)) }
                    IconButton(onClick = { onDelete(b) }, modifier = Modifier.testTag("bookmarkDelete")) { Icon(Icons.Rounded.Delete, stringResource(R.string.bookmark_delete)) }
                }
            },
            modifier = Modifier.fillMaxWidth().clickable { onJump(b) }.testTag("bookmark:${BookmarkRules.defaultTitle(b.timeSec)}"),
        )
    }
}

/** History rows (newest first): when, from → to, how long; tap plays from where the session ended. */
fun LazyListScope.historyItems(entries: List<HistoryEntry>, onJump: (HistoryEntry) -> Unit) {
    items(entries, key = { "hist:${it.id}" }, contentType = { "history" }) { e -> HistoryRow(e) { onJump(e) } }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val device = if (e.thisDevice) stringResource(R.string.history_this_device) else e.deviceName
    val detail = listOfNotNull(
        stringResource(R.string.history_range, formatClock(e.startSec), formatClock(e.endSec)),
        stringResource(R.string.history_listened, listenedText(e.listenedSec)),
        device,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(format.format(Date(e.startedAt))) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("history:${e.id}"),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)

/**
 * Bookmarks of the playing title (a tab of the player's contents sheet): add one at [positionSec] (optional note),
 * jump ([onDone] closes the sheet), edit, delete.
 */
@Composable
fun BookmarksContent(key: String, positionSec: Double, onDone: () -> Unit, viewModel: TitleExtrasViewModel = hiltViewModel()) {
    val rows by remember(key) { viewModel.bookmarks(key) }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf<Double?>(null) }
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    Column(Modifier.fillMaxWidth().testTag("bookmarksTab")) {
        FilledTonalButton(
            onClick = { adding = kotlin.math.floor(positionSec) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("bookmarkAdd"),
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
            Text(stringResource(R.string.bookmark_add_here, formatClock(positionSec)), Modifier.padding(start = 8.dp))
        }
        if (DeviceLibrary.isLocalKey(key)) Hint(stringResource(R.string.bookmarks_device_only))
        else if (!BookmarkRules.isSyncable(key)) Hint(stringResource(R.string.bookmarks_local_only))
        if (rows.isEmpty()) Hint(stringResource(R.string.bookmarks_empty))
        LazyColumn {
            bookmarkItems(rows, onJump = { viewModel.jumpTo(key, it.timeSec); onDone() }, onEdit = { editing = it }, onDelete = viewModel::deleteBookmark)
        }
    }
    adding?.let { at ->
        BookmarkNoteDialog(stringResource(R.string.bookmark_new, formatClock(at)), null, onSave = { viewModel.addBookmark(key, at, it); adding = null }, onDismiss = { adding = null })
    }
    editing?.let { row ->
        BookmarkNoteDialog(stringResource(R.string.bookmark_edit_title), row.note, onSave = { viewModel.editBookmark(row, it); editing = null }, onDismiss = { editing = null })
    }
}

/** Listening history of [key] (a tab of the contents sheet); refreshed from the server when online, else what is stored. */
@Composable
fun HistoryContent(key: String, onDone: () -> Unit, viewModel: TitleExtrasViewModel = hiltViewModel()) {
    LaunchedEffect(key) { viewModel.refreshHistory(key) }
    val history by remember(key) { viewModel.history(key) }.collectAsStateWithLifecycle(History(emptyList(), 0.0))
    val online by viewModel.online.collectAsStateWithLifecycle(true)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("historyTab")) {
        HistorySummary(history, online)
        LazyColumn { historyItems(history.entries) { viewModel.jumpTo(key, it.endSec); onDone() } }
    }
}

@Composable
private fun HistorySummary(history: History, online: Boolean) {
    if (history.entries.isEmpty()) Hint(stringResource(R.string.history_empty))
    else Hint(stringResource(R.string.history_total, listenedText(history.totalListenedSec)))
    if (!online) Hint(stringResource(R.string.history_offline_hint))
}

/** The "play next" queue (a tab of the contents sheet): tap plays now ([onDone] closes the sheet), arrows reorder, cross removes. */
@Composable
fun QueueContent(onDone: () -> Unit, viewModel: TitleExtrasViewModel = hiltViewModel()) {
    val rows by viewModel.queueRows.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("queueTab")) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Hint(stringResource(if (rows.isEmpty()) R.string.queue_empty else R.string.queue_hint), Modifier.weight(1f))
            if (rows.isNotEmpty()) TextButton(onClick = viewModel::clearQueue, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.queue_clear)) }
        }
        LazyColumn {
            items(rows, key = { "q:${it.key}" }) { r ->
                val i = rows.indexOf(r)
                ListItem(
                    headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = r.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.moveInQueue(r.key, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.queue_move_up)) }
                            IconButton(onClick = { viewModel.moveInQueue(r.key, 1) }, enabled = i < rows.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.queue_move_down)) }
                            IconButton(onClick = { viewModel.removeFromQueue(r.key) }) { Icon(Icons.Rounded.Close, stringResource(R.string.queue_remove)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.playFromQueue(r.key); onDone() }.testTag("queue:${r.key}"),
                )
            }
        }
    }
}

/**
 * Detail-page sections: the title's bookmarks (jump/edit/delete; new ones are set in the player) and its listening
 * history (first [HISTORY_PREVIEW] entries, then "show more"). Nothing is shown for a title without either.
 */
fun LazyListScope.titleExtrasSections(
    key: String,
    bookmarks: List<BookmarkEntity>,
    history: History,
    showAllHistory: Boolean,
    online: Boolean,
    onShowAllHistory: () -> Unit,
    onJump: (Double) -> Unit,
    onEditBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
) {
    if (bookmarks.isNotEmpty()) {
        item("bookmarks-title") { SectionTitle(stringResource(R.string.bookmarks_title)) }
        bookmarkItems(bookmarks, onJump = { onJump(it.timeSec) }, onEdit = onEditBookmark, onDelete = onDeleteBookmark)
    }
    if (history.entries.isNotEmpty()) {
        item("history-title") {
            Column {
                SectionTitle(stringResource(R.string.history_title))
                HistorySummary(history, online)
            }
        }
        val shown = if (showAllHistory) history.entries else history.entries.take(HISTORY_PREVIEW)
        historyItems(shown) { onJump(it.endSec) }
        if (shown.size < history.entries.size) {
            item("history-more") { TextButton(onClick = onShowAllHistory, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.history_show_more)) } }
        }
    }
}

const val HISTORY_PREVIEW = 5

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
)
