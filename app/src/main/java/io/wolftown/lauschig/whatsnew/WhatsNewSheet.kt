package io.wolftown.lauschig.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R

/** "Was ist neu" for [releases] (newest first) as a bottom sheet; [onDismiss] on the button, a swipe or "back". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(releases: List<Release>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        WhatsNewContent(releases, onDismiss)
    }
}

@Composable
fun WhatsNewContent(releases: List<Release>, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("whatsNew")) {
        Text(
            stringResource(R.string.whatsnew_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
            releases.forEach { release ->
                item(key = "v${release.versionCode}") {
                    Text(
                        stringResource(R.string.whatsnew_version, release.versionName),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                item(key = "n${release.versionCode}") {
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        stringArrayResource(release.notes).forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(horizontal = 24.dp, vertical = 8.dp).testTag("whatsNewDone")) {
            Text(stringResource(R.string.whatsnew_done))
        }
    }
}
