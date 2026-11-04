package io.wolftown.lauschig.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.theme.rememberCoverSwatch

@Composable
fun YearReviewRoute(onClose: () -> Unit, viewModel: YearReviewViewModel = hiltViewModel()) {
    // Read from the view model's snapshot: the slides and their story's place change together.
    val page = viewModel.page
    YearReviewScreen(page.state, onClose, story = page.story, motion = rememberStoryMotion())
}

/**
 * The year in review („Jahresrückblick“) as a story ([StoryPager]): one slide per part of the year that has data, the
 * cover's tint behind the slides that lead with one title. While loading a quiet page; for a year without an hour of
 * listening a page that says so (with its own close).
 */
@Composable
fun YearReviewScreen(
    state: YearReviewUiState,
    onClose: () -> Unit,
    /** Where the story stands on these slides (the view model keeps it with them, see [YearReviewViewModel.page]). */
    story: StoryState?,
    modifier: Modifier = Modifier,
    motion: StoryMotion = StoryMotion(),
) {
    val review = state.review
    if (state.loading || review == null) {
        Column(modifier.fillMaxSize().testTag("reviewEmpty")) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag("storyClose")) { Icon(Icons.Rounded.Close, stringResource(R.string.review_close)) }
            }
            StatePage(Modifier.weight(1f)) { padding ->
                if (state.loading) {
                    LoadingState(verticalPadding = padding)
                } else {
                    EmptyState(
                        Icons.Rounded.AutoAwesome, stringResource(R.string.review_empty, state.year),
                        supporting = stringResource(R.string.review_empty_hint), verticalPadding = padding,
                    )
                }
            }
        }
        return
    }
    // The tints of the slides that lead with a title start loading now, so each is there when its slide comes.
    review.slides.mapNotNull { it.leadItemId }.distinct().forEach { id ->
        key(id) { rememberCoverSwatch(reviewCoverModel(id, state.covers), state.covers[id]?.version ?: 0) }
    }
    val place = checkNotNull(story) { "a review is shown with its story's place" }
    StoryPager(
        place, onClose, title = stringResource(R.string.review_title, review.year),
        colors = { reviewSlideColors(review.slides[it], state.covers) },
        description = { reviewSlideDescription(review.slides[it]) },
        modifier = modifier,
        motion = motion,
    ) { i, entrance ->
        // A slide fading out after the slides were rebuilt may stand beyond the new ones.
        val slide = review.slides.getOrNull(i) ?: return@StoryPager
        CompositionLocalProvider(LocalSlideBackground provides reviewSlideColors(slide, state.covers).background) {
            ReviewSlideContent(slide, state.covers, entrance)
        }
    }
}
