package io.wolftown.lauschig.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.PositionJump
import io.wolftown.lauschig.sync.PositionJumpBus

/** Picks the pending jump to show: the loaded item's, or the first pending one if nothing is loaded. */
internal fun jumpToShow(pending: List<PositionJump>, loadedItemId: String?): PositionJump? =
    if (loadedItemId == null) pending.firstOrNull() else pending.firstOrNull { it.itemId == loadedItemId }

/** Seeks back to [PositionJump.fromSec]; starts the item there if it is not the loaded one. */
internal fun undoPositionJump(jump: PositionJump, player: PlayerController, state: PlayerUiState, serverId: String?) {
    if (state.itemId == jump.itemId) {
        player.seekTo(jump.fromSec)
    } else if (serverId != null) {
        player.play(serverId, jump.itemId, jump.fromSec)
    }
}

internal fun formatHms(totalSec: Double): String {
    val s = totalSec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Shows "continued on another device, go back to hh:mm:ss?" for a pending [PositionJump] and consumes it
 * once it was answered or dismissed. Renders nothing itself; the snackbar appears in [hostState]'s host.
 */
@Composable
fun PositionJumpSnackbar(
    bus: PositionJumpBus,
    player: PlayerController,
    serverId: String?,
    hostState: SnackbarHostState,
) {
    val pending by bus.pending.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val jump = jumpToShow(pending, playerState.itemId)
    val currentState by rememberUpdatedState(playerState)
    val message = stringResource(R.string.position_jump, formatHms(jump?.fromSec ?: 0.0))
    val actionLabel = stringResource(R.string.position_jump_undo)

    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        val result = hostState.showSnackbar(message, actionLabel, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) undoPositionJump(jump, player, currentState, serverId)
        bus.consume(jump.itemId)
    }
}
