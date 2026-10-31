package io.wolftown.lauschig.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.LibraryKind

@StringRes
private fun LibraryKind.label(): Int? = when (this) {
    LibraryKind.AUDIOBOOK -> R.string.filter_audiobooks
    LibraryKind.HOERSPIEL -> R.string.filter_radio_plays
    LibraryKind.PODCAST -> null // hidden in Phase 1
}

/**
 * "All" plus one chip per kind in [available] (podcasts are not offered yet). An empty [selected] set (or one
 * containing every available kind) means "All". Rendered only when there is something to choose between.
 */
@Composable
fun KindFilterChips(
    available: Set<LibraryKind>,
    selected: Set<LibraryKind>,
    onChange: (Set<LibraryKind>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kinds = LibraryKind.entries.filter { it in available && it.label() != null }
    if (kinds.size < 2) return
    val all = selected.none { it in kinds } || kinds.all { it in selected }
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = all, onClick = { onChange(emptySet()) }, label = { Text(stringResource(R.string.filter_all)) })
        kinds.forEach { kind ->
            FilterChip(
                selected = !all && kind in selected,
                onClick = {
                    val next = if (all) setOf(kind) else if (kind in selected) selected - kind else selected + kind
                    onChange(if (next.none { it in kinds } || kinds.all { it in next }) emptySet() else next)
                },
                label = { Text(stringResource(kind.label()!!)) },
            )
        }
    }
}
