package io.wolftown.lauschig.whatsnew

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WhatsNewResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun notes(locale: Locale, release: Release): List<String> {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).resources.getStringArray(release.notes).toList()
    }

    @Test fun theCurrentVersion_hasNotes() = assertEquals(BuildConfig.VERSION_CODE, WHATS_NEW.first().versionCode)

    @Test fun releases_newestFirst_withVersionNames() {
        assertEquals(WHATS_NEW.sortedByDescending { it.versionCode }, WHATS_NEW)
        assertEquals(BuildConfig.VERSION_NAME, WHATS_NEW.first().versionName)
    }

    @Test fun version030_namesTheAchievements_inBothLanguages() {
        val release = WHATS_NEW.first { it.versionName == "0.3.0" }
        assertTrue(notes(Locale.GERMANY, release).any { it.startsWith("Erfolge:") })
        assertTrue(notes(Locale.US, release).any { it.startsWith("Achievements:") })
    }

    @Test fun everyRelease_hasGermanAndEnglishNotes_ofTheSameLength() {
        for (r in WHATS_NEW) {
            val de = notes(Locale.GERMANY, r)
            val en = notes(Locale.US, r)
            assertTrue("${r.versionName}: no notes", de.isNotEmpty())
            assertEquals("${r.versionName}: languages differ", de.size, en.size)
            assertTrue("${r.versionName}: German missing", de != en)
            (de + en).forEach { assertTrue("too long for a sheet line: $it", it.length in 1..140) }
        }
    }
}
