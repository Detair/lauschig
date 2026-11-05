package io.wolftown.lauschig.ui.podcast

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.ActionRow
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.DownloadIconButton
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.QuickDownload
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.common.progressEntries
import io.wolftown.lauschig.ui.common.rememberProgressActionHost
import io.wolftown.lauschig.ui.detail.Description
import io.wolftown.lauschig.ui.detail.formatClock
import io.wolftown.lauschig.ui.detail.rememberNotificationPermissionOnce
import java.text.DateFormat
import java.util.Date

@Composable
fun PodcastRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionOnce()
    var menuFor by remember { mutableStateOf<EpisodeUi?>(null) }
    PodcastScreen(state, onBack, viewModel::setFilter, modifier) { episode ->
        // Complete comes from the state (no flicker, one source); the per-row flow only adds queued/running/paused/failed.
        val live by remember(episode.key) { actions.downloadState(episode.key) }.collectAsStateWithLifecycle(QuickDownload.None)
        EpisodeRow(
            episode, if (episode.downloaded) QuickDownload.Complete else live,
            onPlay = { viewModel.play(episode.key) },
            onDownload = { actions.download(episode.key); askNotifications() },
            onDeleteDownload = { actions.deleteDownload(episode.key) },
            onLongClick = { menuFor = episode },
        )
    }
    menuFor?.let { EpisodeActionsSheet(it, actions, onDismiss = { menuFor = null }) }
}

/** Header, filter chips and the episode list; [episodeRow] renders one row (the route wires it to the actions). */
@Composable
fun PodcastScreen(
    state: PodcastUiState,
    onBack: () -> Unit,
    onFilter: (EpisodeFilter) -> Unit,
    modifier: Modifier = Modifier,
    episodeRow: @Composable LazyItemScope.(EpisodeUi) -> Unit,
) {
    val settledOffline = LocalOfflineDimming.current
    LazyColumn(modifier.fillMaxSize().testTag("podcastScreen")) {
        item("back") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            }
        }
        if (!state.found) {
            item("missing") { Text(stringResource(R.string.podcast_not_found), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            return@LazyColumn
        }
        item("header") { Header(state) }
        item("filters") { FilterRow(state.filter, onFilter) }
        if (state.incomplete && settledOffline) {
            item("offline-hint") {
                Text(
                    stringResource(R.string.podcast_more_when_online),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("moreWhenOnline"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.episodes.isEmpty() && !state.loading) {
            item("empty") {
                Text(
                    stringResource(R.string.podcast_no_episodes),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.episodes, key = EpisodeUi::key, contentType = { "episode" }) { episodeRow(it) }
    }
}

@Composable
private fun Header(state: PodcastUiState) {
    val model = coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, state.coverVersion)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CoverArt(model, state.title, Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f).testTag("cover"), initialsStyle = MaterialTheme.typography.headlineLarge)
        Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        state.author?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
        Text(
            pluralStringResource(R.plurals.podcast_episode_count, state.episodeCount, state.episodeCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("episodeCount"),
        )
        state.description?.let { Description(it) }
    }
}

@Composable
private fun FilterRow(current: EpisodeFilter, onFilter: (EpisodeFilter) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EpisodeFilter.entries.forEach { f ->
            val label = when (f) {
                EpisodeFilter.ALL -> R.string.podcast_filter_all
                EpisodeFilter.UNPLAYED -> R.string.podcast_filter_unplayed
                EpisodeFilter.DOWNLOADED -> R.string.podcast_filter_downloaded
            }
            FilterChip(selected = f == current, onClick = { onFilter(f) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("filter:$f"))
        }
    }
}

/**
 * One episode: date, title, duration (or time left), progress bar and "heard" tick, play and download buttons.
 * Tap or the play button plays, long-press opens the progress actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    episode: EpisodeUi,
    download: QuickDownload,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val unavailable = LocalOfflineDimming.current && download != QuickDownload.Complete
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val meta = buildList {
        if (episode.publishedAt > 0) add(dateFormat.format(Date(episode.publishedAt)))
        when {
            episode.remainingSec != null -> add(stringResource(R.string.podcast_episode_left, formatClock(episode.remainingSec)))
            episode.durationSec > 0 -> add(formatClock(episode.durationSec))
        }
    }.joinToString(" · ")
    val playLabel = stringResource(R.string.cover_play, episode.title)
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(enabled = !unavailable, onClick = onPlay, onLongClick = onLongClick)
            .testTag("episode:${episode.key}"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (episode.finished) {
                        val heard = stringResource(R.string.podcast_episode_heard)
                        Icon(
                            Icons.Rounded.CheckCircle, null,
                            Modifier.padding(end = 6.dp).size(16.dp).semantics { contentDescription = heard }.testTag("episodeHeard"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (episode.finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!unavailable) {
                IconButton(onClick = onPlay, modifier = Modifier.testTag("episodePlay")) { Icon(Icons.Rounded.PlayArrow, playLabel) }
            }
            DownloadIconButton(download, onDownload, onDeleteDownload)
        }
        if (episode.progress > 0f) {
            LinearProgressIndicator(progress = { episode.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("episodeProgress"))
        } else {
            Spacer(Modifier.size(4.dp))
        }
    }
}

/** Long-press menu of an episode: the progress actions (mark heard, start over, hide, fetch from server). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionsSheet(episode: EpisodeUi, actions: ItemActionsViewModel, onDismiss: () -> Unit) {
    val download by remember(episode.key) { actions.downloadState(episode.key) }.collectAsStateWithLifecycle(QuickDownload.None)
    val progress by remember(episode.key) { actions.progressState(episode.key) }.collectAsStateWithLifecycle(null)
    val host = rememberProgressActionHost(episode.key, actions)
    val offline = LocalOfflineDimming.current
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("episodeActions")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val context = LocalContext.current
            ActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.queue_add)) {
                actions.enqueue(episode.key)
                Toast.makeText(context, R.string.queue_added, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            progressEntries(progress, offline).forEach { entry ->
                ListItem(
                    headlineContent = { Text(stringResource(entry.label(progress))) },
                    leadingContent = { Icon(entry.icon, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { host.run(entry); onDismiss() },
                )
            }
        }
    }
}
