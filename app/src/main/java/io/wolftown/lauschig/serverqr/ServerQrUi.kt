package io.wolftown.lauschig.serverqr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import kotlin.math.floor

/**
 * [modules] as a picture: black on white with the light border a scanner needs, in every theme (a dark or black design
 * must not turn the code light on dark). Modules are whole pixels wide, so there are no seams between them.
 */
@Composable
fun QrCodeImage(modules: QrModules, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description; role = Role.Image }) {
        drawRect(Color.White)
        val cell = floor(size.minDimension / (modules.size + 2 * QrCode.QUIET_ZONE))
        if (cell < 1f) return@Canvas
        val left = floor((size.width - cell * modules.size) / 2)
        val top = floor((size.height - cell * modules.size) / 2)
        for (y in 0 until modules.size) {
            var x = 0
            while (x < modules.size) {
                if (!modules[x, y]) {
                    x++
                    continue
                }
                // A run of dark modules is one rectangle.
                var end = x
                while (end + 1 < modules.size && modules[end + 1, y]) end++
                drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size((end - x + 1) * cell, cell))
                x = end + 1
            }
        }
    }
}

/**
 * "Server-Adressen als QR-Code": the code, what is in it as plain text, and the sentence that nothing else is.
 * [text] and [addresses] come from [ServerQr.text] and [ServerQr.shown] for the same account.
 */
@Composable
fun ServerQrDialog(text: String, addresses: List<String>, onClose: () -> Unit) {
    val modules = remember(text) { QrCode.modules(text) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.server_qr_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrCodeImage(
                    modules, stringResource(R.string.server_qr_description),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag("serverQrCode"),
                )
                Text(stringResource(R.string.server_qr_only_addresses), style = MaterialTheme.typography.bodyMedium)
                addresses.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("serverQrAddress"))
                }
                Text(stringResource(R.string.server_qr_how), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("serverQrClose")) { Text(stringResource(R.string.server_qr_close)) } },
        modifier = Modifier.testTag("serverQrDialog"),
    )
}
