package io.wolftown.lauschig.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The one symbol for "this title is on this device" (cover badge, download button, episode rows, the player's source):
 * the arrow in a circle, the streaming apps' "downloaded". Not the tick on a bar (`DownloadDone`), which reads like a
 * done to-do, and not `OfflinePin`, whose tick in a circle looks like the "heard" tick (`CheckCircle`) next to it.
 */
val OfflineIcon: ImageVector get() = Icons.Rounded.ArrowCircleDown
