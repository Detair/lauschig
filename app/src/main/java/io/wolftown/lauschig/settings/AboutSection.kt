package io.wolftown.lauschig.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.R
import io.wolftown.lauschig.whatsnew.WHATS_NEW
import io.wolftown.lauschig.whatsnew.WhatsNewSheet

/** "Über": the app version and every release's "Was ist neu". */
@Composable
fun AboutSection(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE) {
    var showNews by rememberSaveable { mutableStateOf(false) }
    Section(R.string.settings_section_about)
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_version)) },
        supportingContent = { Text("$versionName ($versionCode)") },
        modifier = Modifier.testTag("appVersion"),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_whats_new)) },
        supportingContent = { Text(stringResource(R.string.settings_whats_new_hint)) },
        modifier = Modifier.fillMaxWidth().clickable { showNews = true }.testTag("openWhatsNew"),
    )
    if (showNews) WhatsNewSheet(WHATS_NEW) { showNews = false }
}
