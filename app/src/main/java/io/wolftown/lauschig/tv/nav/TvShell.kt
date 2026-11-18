package io.wolftown.lauschig.tv.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TvDimens

/** The rail's items are tagged with this plus the section's name. */
internal const val TAG_RAIL = "tvRail:"

/** The TV's top-level pages, reached from the rail. */
enum class TvSection(@StringRes val label: Int, val icon: ImageVector) {
    Home(R.string.nav_start, Icons.Rounded.Home),
    Library(R.string.nav_library, Icons.Rounded.LibraryBooks),
    Search(R.string.nav_search, Icons.Rounded.Search),
    Settings(R.string.settings_title, Icons.Rounded.Settings),
}

/**
 * Whether the rail of the page has the focus right now. Content that takes the focus by itself when it appears (a message,
 * rows that arrive) leaves it alone then: the person is choosing a section.
 */
internal val LocalRailHasFocus = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Content that must keep the focus (a PIN being typed) closes the rail while it is on screen ([KeepRailClosed]). */
@Stable
internal class RailLock {
    var holders by mutableIntStateOf(0)
}

internal val LocalRailLock = staticCompositionLocalOf<RailLock?> { null }

/**
 * While [active], the rail does not open: Left at the edge stays where it is. A section chosen there would end a PIN half typed,
 * and the first of two entries with it.
 */
@Composable
internal fun KeepRailClosed(active: Boolean) {
    if (!active) return
    val lock = LocalRailLock.current ?: return
    DisposableEffect(lock) {
        lock.holders++
        onDispose { lock.holders-- }
    }
}

/**
 * A top-level page with the rail on its left. The rail shows only its symbols until the focus comes in: left from the content
 * opens it on the [current] section, with the names. Right, OK on the current section, or Back goes back to where the focus
 * was in the content; OK on another section calls [onSection].
 */
@Composable
fun TvShell(current: TvSection, onSection: (TvSection) -> Unit, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val currentItem = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    val lock = remember { RailLock() }
    // Back closes the open rail, as on other TV apps, instead of leaving the page behind it.
    BackHandler(enabled = railHasFocus) { focusManager.moveFocus(FocusDirection.Right) }
    NavigationDrawer(
        drawerContent = {
            Column(
                Modifier
                    .fillMaxHeight()
                    .padding(start = TvDimens.overscanHorizontal / 2, end = 8.dp, top = TvDimens.overscanVertical, bottom = TvDimens.overscanVertical)
                    .selectableGroup()
                    .onFocusChanged { railHasFocus = it.hasFocus }
                    // Into the rail only by left from the content, and then on the page one is on, not on the item that happens
                    // to be level with the focus. When the content's focused element goes (a loading message replaced by the
                    // rows), Android hands the focus back to the window, and Compose would give it to the first thing it finds:
                    // the rail, which then opens by itself and keeps the rows from taking the focus.
                    .focusProperties {
                        onEnter = {
                            if (requestedFocusDirection == FocusDirection.Left && lock.holders == 0) currentItem.requestFocus(FocusDirection.Enter)
                            else cancelFocusChange()
                        }
                    }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                TvSection.entries.forEach { section ->
                    val isCurrent = section == current
                    NavigationDrawerItem(
                        selected = isCurrent,
                        onClick = { if (isCurrent) focusManager.moveFocus(FocusDirection.Right) else onSection(section) },
                        leadingContent = { Icon(section.icon, contentDescription = null) },
                        modifier = Modifier
                            .then(if (isCurrent) Modifier.focusRequester(currentItem) else Modifier)
                            .testTag(TAG_RAIL + section.name),
                    ) {
                        Text(stringResource(section.label))
                    }
                }
            }
        },
    ) {
        // Back from the rail: the card that had the focus, not the one level with the rail item.
        Box(
            Modifier
                .fillMaxSize()
                .padding(end = TvDimens.overscanHorizontal, top = TvDimens.overscanVertical, bottom = TvDimens.overscanVertical)
                .focusRestorer()
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalRailHasFocus provides { railHasFocus }, LocalRailLock provides lock, content = content)
        }
    }
}
