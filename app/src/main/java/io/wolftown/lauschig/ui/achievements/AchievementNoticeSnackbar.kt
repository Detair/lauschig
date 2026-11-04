package io.wolftown.lauschig.ui.achievements

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.Achievement

/**
 * „Neuer Erfolg: 7 Tage am Stück“ (several: „3 neue Erfolge“) with „Ansehen“ ([onOpen]; no action without it) in
 * [hostState]'s host. Once the snackbar ended – dismissed, timed out or acted on – [onShown] marks the notice told. A
 * newer notice replaces the one on screen, and leaving the screen ends it: then it counts as not shown and comes again.
 * The timeout also runs while the app is in the background, so a notice that appears just before the phone is put away
 * counts as told (accepted: "told once" rather than "told until seen").
 */
@Composable
fun AchievementNoticeSnackbar(
    notice: AchievementNotice?,
    hostState: SnackbarHostState,
    onShown: (AchievementNotice) -> Unit,
    onOpen: (() -> Unit)?,
) {
    val single = notice?.unlocks?.singleOrNull()?.let { key -> Achievement.byId(key.id)?.let { it to key.tier } }
    val message = when {
        notice == null -> null
        single != null -> stringResource(R.string.achievement_notice_one, achievementName(single.first, single.second))
        else -> pluralStringResource(R.plurals.achievement_notice_many, notice.unlocks.size, notice.unlocks.size)
    }
    val action = stringResource(R.string.achievement_notice_view).takeIf { onOpen != null }
    val open by rememberUpdatedState(onOpen)
    val done by rememberUpdatedState(onShown)
    LaunchedEffect(notice) {
        if (notice == null || message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(message, actionLabel = action, withDismissAction = true, duration = SnackbarDuration.Long)
        done(notice)
        if (result == SnackbarResult.ActionPerformed) open?.invoke()
    }
}
