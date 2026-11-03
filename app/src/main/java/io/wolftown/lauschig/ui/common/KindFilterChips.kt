package io.wolftown.lauschig.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import io.wolftown.lauschig.catalog.Facet
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
private fun LibraryKind.label(): Int = when (this) {
    LibraryKind.AUDIOBOOK -> R.string.filter_audiobooks
    LibraryKind.HOERSPIEL -> R.string.filter_radio_plays
    LibraryKind.PODCAST -> R.string.filter_podcasts
}

/**
 * "All" plus one chip per kind in [available]. An empty [selected] set (or one
 * containing every available kind) means "All". The kind chips are rendered only when there is something to choose
 * between; [trailing] (e.g. a tag filter) follows them in the same scrolling row.
 */
@Composable
fun KindFilterChips(
    available: Set<LibraryKind>,
    selected: Set<LibraryKind>,
    onChange: (Set<LibraryKind>) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val kinds = LibraryKind.entries.filter { it in available }
    val showKinds = kinds.size >= 2
    if (!showKinds && trailing == null) return
    val all = selected.none { it in kinds } || kinds.all { it in selected }
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showKinds) FilterChip(selected = all, onClick = { onChange(emptySet()) }, label = { Text(stringResource(R.string.filter_all)) })
        if (showKinds) kinds.forEach { kind ->
            FilterChip(
                selected = !all && kind in selected,
                onClick = {
                    val next = if (all) setOf(kind) else if (kind in selected) selected - kind else selected + kind
                    onChange(if (next.none { it in kinds } || kinds.all { it in next }) emptySet() else next)
                },
                label = { Text(stringResource(kind.label())) },
            )
        }
        trailing?.invoke()
    }
}

/**
 * Tag filter: a chip naming the chosen tag (or "Tags") that opens a menu with every tag of [tags] and its number of
 * titles, plus "Alle Tags". Nothing is rendered without tags.
 */
@Composable
fun TagFilterChip(tags: List<Facet>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected ?: stringResource(R.string.filter_tags)) },
            leadingIcon = { Icon(Icons.Rounded.Sell, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag("tagFilter"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_tags_all)) },
                onClick = { open = false; onSelect(null) },
                modifier = Modifier.testTag("tag:all"),
            )
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filter_tag_count, tag.name, tag.count)) },
                    onClick = { open = false; onSelect(tag.name) },
                    trailingIcon = if (tag.name.equals(selected, ignoreCase = true)) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    modifier = Modifier.testTag("tag:${tag.name}"),
                )
            }
        }
    }
}
