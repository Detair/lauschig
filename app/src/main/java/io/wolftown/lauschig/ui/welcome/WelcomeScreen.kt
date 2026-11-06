package io.wolftown.lauschig.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.settings.UiSettings
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Only for someone new: not seen yet ([seen]), and no sign of an older version on the device – Android says the package was
 * [updated], or a stored "Was ist neu" number is older than the [currentVersion]. The number of this very version
 * does not count: the first start stored it, and a restart in the middle of the welcome finds it.
 * That nobody is signed in and no profile is saved is the caller's part.
 */
fun welcomeIsDue(seen: Boolean, lastSeenVersion: Int?, currentVersion: Int, updated: Boolean): Boolean =
    !seen && !updated && (lastSeenVersion == null || lastSeenVersion >= currentVersion)

private class WelcomePage(val icon: ImageVector, @StringRes val title: Int, @StringRes val text: Int)

private val WELCOME_PAGES = listOf(
    WelcomePage(Icons.Rounded.Headphones, R.string.welcome_1_title, R.string.welcome_1_text),
    WelcomePage(Icons.Rounded.Dns, R.string.welcome_2_title, R.string.welcome_2_text),
    WelcomePage(Icons.Rounded.DownloadForOffline, R.string.welcome_3_title, R.string.welcome_3_text),
)

val WELCOME_PAGE_COUNT: Int get() = WELCOME_PAGES.size

/** The page lives in the route's [SavedStateHandle]: it survives turning the device and a process death. */
@HiltViewModel
class WelcomeViewModel(private val savedState: SavedStateHandle, private val markSeen: suspend () -> Unit) : ViewModel() {
    @Inject constructor(savedState: SavedStateHandle, ui: UiSettings) : this(savedState, ui::setWelcomeSeen)

    val page: StateFlow<Int> = savedState.getStateFlow(KEY_PAGE, 0)

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** The next page; after the last one the welcome is over. */
    fun next() {
        if (page.value < WELCOME_PAGE_COUNT - 1) savedState[KEY_PAGE] = page.value + 1 else finish()
    }

    /** A page back; false on the first one. */
    fun back(): Boolean {
        if (page.value == 0) return false
        savedState[KEY_PAGE] = page.value - 1
        return true
    }

    fun skip() = finish()

    /** Seen only now, at the end or when skipped: leaving in the middle shows it again next time. */
    private fun finish() {
        viewModelScope.launch {
            try {
                markSeen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not remember the welcome", e) // it comes once more, the sign-in is not in question
            }
            _done.value = true
        }
    }

    private companion object {
        const val KEY_PAGE = "welcome_page"
        const val TAG = "Welcome"
    }
}

@Composable
fun WelcomeRoute(onDone: () -> Unit, viewModel: WelcomeViewModel = hiltViewModel()) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onDone() }
    BackHandler(enabled = page > 0) { viewModel.back() }
    WelcomeScreen(page, viewModel::next, viewModel::skip)
}

/** The very first start: a few calm pages on what the app is and what it needs, then the sign-in. */
@Composable
fun WelcomeScreen(page: Int, onNext: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val last = page == WELCOME_PAGES.lastIndex
    Column(modifier.fillMaxSize().testTag("welcome")) {
        val shownNow = WELCOME_PAGES[page]
        val title = stringResource(shownNow.title)
        val text = stringResource(shownNow.text)
        StatePage(Modifier.weight(1f)) { verticalPadding ->
            // One node for TalkBack, the same on every page: a live region speaks when a node it knows changes, and the
            // cross-fade's pages would each be new. They are only for the eye.
            Box(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    testTag = "welcomePage"
                    contentDescription = "$title. $text"
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(page, label = "welcome") { shown ->
                    val content = WELCOME_PAGES[shown]
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.widthIn(max = 480.dp).padding(horizontal = Spacing.xl, vertical = verticalPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(content.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                            Text(
                                stringResource(content.title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = Spacing.xl),
                            )
                            Text(
                                stringResource(content.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = Spacing.m),
                            )
                        }
                    }
                }
            }
        }
        // On one line where they fit. Large text on a narrow phone: they move to the next line, none breaks inside its word.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PlaceDots(page)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!last) {
                    TextButton(onClick = onSkip, modifier = Modifier.testTag("welcomeSkip")) { Text(stringResource(R.string.welcome_skip)) }
                }
                Button(onClick = onNext, modifier = Modifier.testTag("welcomeNext")) {
                    Text(stringResource(if (last) R.string.welcome_sign_in else R.string.welcome_next))
                }
            }
        }
    }
}

@Composable
private fun PlaceDots(page: Int) {
    val place = stringResource(R.string.welcome_place, page + 1, WELCOME_PAGES.size)
    Row(
        Modifier.padding(start = Spacing.s).clearAndSetSemantics { contentDescription = place },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        WELCOME_PAGES.indices.forEach { i ->
            val color = if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}
