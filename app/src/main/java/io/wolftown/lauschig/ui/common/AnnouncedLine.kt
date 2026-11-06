package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A line that appears when something happens and is read out by TalkBack. Compose sends a content event with the new words
 * only for a node that was already in the accessibility tree and has changed; a node that is new gets at most a subtree change.
 * So this one is always there: without [text] a sliver without words that takes no visible room (without a size it would
 * not be in the tree), with it the line, plus [padding] around it. Like every node it is in the tree only while on screen: put
 * it where the person is looking.
 */
@Composable
fun AnnouncedLine(
    text: String?,
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 1.dp).clearAndSetSemantics {
            testTag = tag
            liveRegion = LiveRegionMode.Polite
            if (text != null) contentDescription = text
        },
    ) {
        if (text != null) Text(text, style = style, color = color, modifier = Modifier.padding(padding))
    }
}
