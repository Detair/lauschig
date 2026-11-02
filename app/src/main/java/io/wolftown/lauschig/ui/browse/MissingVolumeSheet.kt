package io.wolftown.lauschig.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.external.MissingVolume
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Details of a volume the library lacks (from Audible). [actions] is the slot for the request button below the details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingVolumeSheet(volume: MissingVolume, onDismiss: () -> Unit, actions: @Composable () -> Unit) {
    val v = volume.volume
    val summary = remember(v.summary) { v.summary?.let(::plainText) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = v.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(v.title, style = MaterialTheme.typography.titleMedium)
                    v.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (v.authors.isNotEmpty()) Meta(stringResource(R.string.missing_by, v.authors.joinToString()))
                    if (v.narrators.isNotEmpty()) Meta(stringResource(R.string.missing_narrated, v.narrators.joinToString()))
                    v.runtimeMin?.takeIf { it > 0 }?.let { Meta(durationText(it)) }
                    v.releaseDate?.let { Meta(stringResource(if (volume.upcoming) R.string.missing_releases else R.string.missing_released, formatDate(it))) }
                }
            }
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) { actions() }
        }
    }
}

@Composable
private fun Meta(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun durationText(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.missing_duration_min, minutes)
    else stringResource(R.string.missing_duration_h_min, minutes / 60, minutes % 60)

internal fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Audible summaries are small HTML snippets; the sheet shows them as plain text. */
internal fun plainText(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace("\uFFFC", "").trim()
