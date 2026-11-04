package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.theme.Spacing
import kotlinx.coroutines.delay

/** How long a page may take before [LoadingState] shows its indicator; most first loads are done before. */
const val LOADING_INDICATOR_DELAY_MS = 400L

/**
 * A list page whose first data is not there yet: nothing at first, then a quiet centred progress indicator – never the
 * empty state, which would claim there is nothing.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MS)
        show = true
    }
    val label = stringResource(R.string.state_loading)
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("loadingState"), contentAlignment = Alignment.Center) {
        if (show) CircularProgressIndicator(Modifier.size(32.dp).semantics { contentDescription = label }, strokeWidth = 3.dp)
    }
}

/**
 * A list page that has loaded and holds nothing: an icon, one [headline], one [supporting] line in plain words and an
 * [action] where one helps.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(
            headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.l),
        )
        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        action?.let { Box(Modifier.padding(top = Spacing.l)) { it() } }
    }
}
