package io.wolftown.lauschig.ui.common

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction

/**
 * What an accessibility service gets for this node; null if it is not in the accessibility tree. A live region speaks only
 * when a node that was already in this tree changes: a new node, or one without a size, gets no event of its own.
 */
fun SemanticsNodeInteraction.accessibilityInfo(): AccessibilityNodeInfo? {
    val node = fetchSemanticsNode()
    return (node.root as ViewRootForTest).view.accessibilityNodeProvider?.createAccessibilityNodeInfo(node.id)
}
