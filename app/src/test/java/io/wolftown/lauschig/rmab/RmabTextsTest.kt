package io.wolftown.lauschig.rmab

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * With the build switch off no screen may name ReadMeABook. The feature's own texts (`rmab_*` and its settings heading)
 * are only shown by its own screens, which are behind the switch; every other text of the app must not name it at all,
 * "Was ist neu" included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RmabTextsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resources(locale: Locale): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).resources
    }

    private fun own(name: String) = name.startsWith("rmab_") || name == "settings_section_rmab"

    private fun named(text: CharSequence?) = text != null && (text.contains("RMAB", ignoreCase = true) || text.contains("ReadMeABook", ignoreCase = true))

    private fun ids(type: Class<*>): Map<String, Int> = type.fields.associate { it.name to it.getInt(null) }

    @Test fun noTextOutsideTheFeature_namesIt_inEitherLanguage() {
        val strings = ids(R.string::class.java)
        val arrays = ids(R.array::class.java)
        val plurals = ids(R.plurals::class.java)
        assertTrue("the test reads the app's texts", strings.size > 300 && arrays.isNotEmpty() && plurals.isNotEmpty())
        val found = mutableListOf<String>()
        for (locale in listOf(Locale.US, Locale.GERMANY)) {
            val res = resources(locale)
            strings.filterKeys { !own(it) }.forEach { (name, id) -> if (named(res.getText(id))) found += "$locale string/$name" }
            arrays.forEach { (name, id) -> if (res.getTextArray(id).any(::named)) found += "$locale array/$name" }
            plurals.forEach { (name, id) -> if (listOf(1, 2, 5).any { named(res.getQuantityText(id, it)) }) found += "$locale plurals/$name" }
        }
        assertEquals(emptyList<String>(), found)
    }

    /** The control: the feature's own texts do name it, so the check above can see the word. */
    @Test fun theFeaturesOwnTexts_doNameIt() {
        assertTrue(named(resources(Locale.US).getText(R.string.settings_section_rmab)))
        assertTrue(named(resources(Locale.GERMANY).getText(R.string.rmab_hint)))
    }

    /**
     * The switch itself: the Gradle property `lauschig.rmab` (handed to the tests as it was given, see
     * app/build.gradle.kts). Not given, the build has no ReadMeABook.
     */
    @Test fun theBuild_hasTheFeature_onlyWhenAskedFor() {
        val asked = System.getProperty("lauschig.rmab.asked").orEmpty()
        assertEquals("lauschig.rmab=$asked", asked == "true", BuildConfig.RMAB)
        assertEquals(BuildConfig.RMAB, RmabFeature.ENABLED)
    }
}
