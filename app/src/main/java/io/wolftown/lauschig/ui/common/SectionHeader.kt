package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R

/** A section's title: calm (semi-bold, in the text colour), the same on every page. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** [SectionTitle] on the start side, an optional [action] (e.g. [SeeAllButton]) on the end side; one height with or without it. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title, Modifier.weight(1f).padding(vertical = 8.dp))
        action?.invoke()
    }
}

/** "See all" (or [label]) with a chevron; [description] is what TalkBack says instead of the label. */
@Composable
fun SeeAllButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, description: String? = null) {
    TextButton(
        onClick = onClick, contentPadding = PaddingValues(start = 12.dp, end = 6.dp),
        modifier = modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Text(label ?: stringResource(R.string.start_see_all))
        Spacer(Modifier.width(2.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}
