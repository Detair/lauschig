package io.wolftown.lauschig.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.nav.TabLayout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationSettingsViewModel @Inject constructor(private val ui: UiSettings) : ViewModel() {
    val layout: StateFlow<TabLayout> = ui.tabLayout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabLayout.DEFAULT)

    fun move(tab: TopLevelTab, by: Int) = change { it.moved(tab, by) }
    fun setVisible(tab: TopLevelTab, visible: Boolean) = change { it.withHidden(tab, !visible) }
    fun setStart(tab: TopLevelTab) = change { it.withStart(tab) }
    fun reset() = change { TabLayout.DEFAULT }

    private fun change(f: (TabLayout) -> TabLayout) {
        viewModelScope.launch { ui.updateTabLayout(f) }
    }
}

/** Callbacks of [NavigationSettingsScreen]. */
class NavigationSettingsActions(
    val onMove: (TopLevelTab, Int) -> Unit = { _, _ -> },
    val onVisible: (TopLevelTab, Boolean) -> Unit = { _, _ -> },
    val onStart: (TopLevelTab) -> Unit = {},
    val onReset: () -> Unit = {},
)

@Composable
fun NavigationSettingsRoute(onBack: () -> Unit, viewModel: NavigationSettingsViewModel = hiltViewModel()) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    NavigationSettingsScreen(
        layout, onBack,
        NavigationSettingsActions(viewModel::move, viewModel::setVisible, viewModel::setStart, viewModel::reset),
    )
}

/**
 * Every tab with up/down buttons (accessible without drag gestures) and a visibility switch; below, the start screen
 * among the visible tabs. The bar changes at once; the start screen is used from the next app start.
 */
@Composable
fun NavigationSettingsScreen(layout: TabLayout, onBack: () -> Unit, actions: NavigationSettingsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("navigationSettings")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.nav_settings_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            stringResource(R.string.nav_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Section(R.string.nav_settings_tabs)
        layout.order.forEach { tab -> TabRow(tab, layout, actions) }
        Section(R.string.nav_settings_start)
        Column(Modifier.selectableGroup()) {
            layout.visible.forEach { tab ->
                ListItem(
                    headlineContent = { Text(stringResource(tab.label)) },
                    leadingContent = { RadioButton(selected = tab == layout.start, onClick = null) },
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = tab == layout.start, role = Role.RadioButton) { actions.onStart(tab) }
                        .testTag("start:${tab.name}"),
                )
            }
        }
        Text(
            stringResource(R.string.nav_settings_start_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        TextButton(onClick = actions.onReset, enabled = layout != TabLayout.DEFAULT, modifier = Modifier.padding(8.dp).testTag("navReset")) {
            Text(stringResource(R.string.nav_settings_reset))
        }
    }
}

@Composable
private fun TabRow(tab: TopLevelTab, layout: TabLayout, actions: NavigationSettingsActions) {
    val name = stringResource(tab.label)
    val visible = tab !in layout.hidden
    // Start is always there; the last optional visible tab cannot go either.
    val canToggle = if (visible) layout.canHide(tab) else true
    val visibleLabel = stringResource(R.string.nav_settings_visible, name)
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = when {
            tab == TopLevelTab.Start -> ({ Text(stringResource(R.string.nav_settings_always)) })
            !visible -> ({ Text(stringResource(R.string.nav_settings_hidden)) })
            else -> null
        },
        leadingContent = { Icon(tab.icon, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.onMove(tab, -1) }, enabled = layout.canMove(tab, -1), modifier = Modifier.testTag("up:${tab.name}")) {
                    Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.nav_settings_up, name))
                }
                IconButton(onClick = { actions.onMove(tab, +1) }, enabled = layout.canMove(tab, +1), modifier = Modifier.testTag("down:${tab.name}")) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.nav_settings_down, name))
                }
                Switch(
                    checked = visible,
                    onCheckedChange = { actions.onVisible(tab, it) },
                    enabled = canToggle,
                    modifier = Modifier.padding(start = 4.dp).testTag("visible:${tab.name}").semantics { contentDescription = visibleLabel },
                )
            }
        },
    )
}
