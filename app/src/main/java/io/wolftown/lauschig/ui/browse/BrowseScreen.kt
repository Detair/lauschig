package io.wolftown.lauschig.ui.browse

import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.common.SectionTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.external.MissingVolume
import io.wolftown.lauschig.rmab.RmabMissingActions
import io.wolftown.lauschig.rmab.RmabStatus
import io.wolftown.lauschig.rmab.RmabViewModel
import io.wolftown.lauschig.rmab.label
import io.wolftown.lauschig.rmab.tileIcon
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.detail.Description
import io.wolftown.lauschig.ui.nav.BrowseKind

@Composable
fun BrowseRoute(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
    rmab: RmabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    val rmabState by rmab.state.collectAsStateWithLifecycle()
    BrowseScreen(
        state, onBack, viewModel::setKinds, onOpenItem, onOpenBrowse, modifier,
        onPlay = actions::play, onMenu = { menuFor = it },
        missingActions = { RmabMissingActions(it.volume, rmabState, rmab::request) },
        missingStatuses = if (rmabState.connected) rmabState.statuses else emptyMap(),
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

/** Header (name, count, author photo/bio), kind filter and the titles; tap opens details, ▶ plays, long-press opens the menu. */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onBack: () -> Unit,
    onKinds: (Set<LibraryKind>) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (itemId: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
    /** Slot below the details of a missing volume (the request button). */
    missingActions: @Composable (MissingVolume) -> Unit = {},
    /** ReadMeABook status by ASIN; the missing tile shows a small symbol for it. */
    missingStatuses: Map<String, RmabStatus> = emptyMap(),
) {
    var sheetFor by remember { mutableStateOf<MissingVolume?>(null) }
    Column(modifier.fillMaxSize()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
        KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.tileGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.tileGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { Header(state) }
            if (state.series.isNotEmpty()) {
                item(key = "series", span = { GridItemSpan(maxLineSpan) }, contentType = "series") {
                    Column {
                        SectionTitle(stringResource(R.string.browse_series_row))
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.series.forEach { s ->
                                AssistChip(onClick = { onOpenBrowse(BrowseKind.SERIES, s.name) }, label = { Text(s.name) })
                            }
                        }
                    }
                }
            }
            if (state.loaded && state.entries.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Text(stringResource(R.string.browse_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(state.entries, key = { it.key() }, contentType = { if (it is BrowseEntry.Tile) "tile" else "missing" }) { entry ->
                when (entry) {
                    is BrowseEntry.Tile -> CoverTile(
                        entry.item,
                        onClick = { onOpenItem(entry.item.id) }, onLongClick = { onMenu(entry.item) }, onPlay = { onPlay(entry.item.id) },
                    )
                    is BrowseEntry.Missing -> MissingTile(entry, entry.volume?.let { missingStatuses[it.volume.asin] }, onOpen = { sheetFor = it })
                }
            }
        }
    }
    sheetFor?.let { v -> MissingVolumeSheet(v, onDismiss = { sheetFor = null }) { missingActions(v) } }
}

private fun BrowseEntry.key(): String = when (this) {
    is BrowseEntry.Tile -> item.key
    is BrowseEntry.Missing -> "missing:$sequence"
}

@Composable
private fun Header(state: BrowseUiState) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AuthorImage(state)
            Column {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    pluralStringResource(R.plurals.browse_title_count, state.count, state.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.author?.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
    }
}

/** Only while online (the image needs the server); the initials underneath stay if loading fails. */
@Composable
private fun AuthorImage(state: BrowseUiState) {
    val author = state.author ?: return
    val base = LocalServerBaseUrl.current
    if (author.imagePath == null || base == null) return
    val url = remember(base, author.id) {
        base.newBuilder().addPathSegments("api/authors").addPathSegment(author.id).addPathSegment("image").build().toString()
    }
    val description = stringResource(R.string.browse_author_image, state.name)
    CoverArt(
        url, state.name, Modifier.size(96.dp).testTag("authorImage").semantics { contentDescription = description },
        shape = CircleShape, initialsStyle = MaterialTheme.typography.headlineSmall,
    )
}

/** Greyed stand-in for a volume number the library lacks; with Audible data it shows the cover and opens the details. */
@Composable
private fun MissingTile(entry: BrowseEntry.Missing, status: RmabStatus?, onOpen: (MissingVolume) -> Unit) {
    val volume = entry.volume
    val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val hint = when {
        volume == null -> stringResource(R.string.browse_missing_hint)
        volume.upcoming && volume.volume.releaseDate != null -> stringResource(R.string.missing_releases, formatDate(volume.volume.releaseDate!!))
        else -> stringResource(R.string.missing_not_in_library)
    }
    val description = stringResource(R.string.missing_tile_description, entry.sequence)
    val click = if (volume == null) Modifier else Modifier.clickable(role = Role.Button) { onOpen(volume) }
    Column(Modifier.testTag("missingVolume").then(click).semantics(mergeDescendants = true) { if (volume != null) contentDescription = description }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            if (volume == null) {
                Text(stringResource(R.string.browse_missing_volume, entry.sequence), style = MaterialTheme.typography.titleMedium, color = grey)
            } else {
                AsyncImage(
                    model = volume.volume.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    alpha = 0.45f, colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    entry.sequence,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                val icon = status?.tileIcon()
                if (icon != null) {
                    Icon(
                        icon, contentDescription = status.label()?.let { stringResource(it) },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).testTag("rmabTileStatus")
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(3.dp),
                    )
                }
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.padding(top = 4.dp))
    }
}
