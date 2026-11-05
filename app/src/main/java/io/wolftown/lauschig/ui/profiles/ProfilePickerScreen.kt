package io.wolftown.lauschig.ui.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.theme.Spacing

/**
 * „Wer hört zu?“ after a sign-out with other profiles on the device, in place of the sign-in. Nothing switches by itself: the next
 * profile may be protected, and the next person is not necessarily the one it would be.
 */
@Composable
fun ProfilePickerRoute(onAdd: () -> Unit, onNoneLeft: () -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val rows by viewModel.profiles.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    val list = rows ?: return
    if (list.isEmpty()) LaunchedEffect(Unit) { onNoneLeft() }
    ProfilePickerScreen(list, viewModel::switchTo, viewModel::askRemove, onAdd)
    removal?.let { ProfileRemoveDialog(it, viewModel::confirmRemove, viewModel::cancelRemove) }
}

@Composable
fun ProfilePickerScreen(rows: List<ProfileRow>, onSwitch: (ProfileRow) -> Unit, onRemove: (ProfileRow) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    StatePage(modifier) { verticalPadding ->
        Column(Modifier.fillMaxWidth().padding(vertical = verticalPadding).testTag("profilePicker")) {
            Text(
                stringResource(R.string.profiles_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.m).semantics { heading() },
            )
            ProfileList(rows, onSwitch, onRemove, onAdd)
        }
    }
}
