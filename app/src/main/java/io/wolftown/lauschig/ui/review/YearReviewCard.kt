package io.wolftown.lauschig.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.theme.Spacing

/**
 * The Start screen's invitation to the year in review (December and January): one calm card in the primary container
 * with the year; a tap opens the review, the X hides it for that year.
 */
@Composable
fun YearReviewCard(year: Int, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier, maxWidth: Dp = 560.dp) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(max = maxWidth).fillMaxWidth().testTag("reviewCard"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.review_card_open), role = Role.Button, onClick = onOpen)
                    .semantics(mergeDescendants = true) {}
                    .padding(start = Spacing.m, top = Spacing.m, bottom = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(32.dp))
                Column(Modifier.padding(start = Spacing.m)) {
                    Text(stringResource(R.string.review_card_title, year), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.review_card_text, year), style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(
                onClick = onHide,
                colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onPrimaryContainer),
                modifier = Modifier.padding(end = Spacing.xs).testTag("reviewCardHide"),
            ) { Icon(Icons.Rounded.Close, stringResource(R.string.review_card_hide)) }
        }
    }
}
