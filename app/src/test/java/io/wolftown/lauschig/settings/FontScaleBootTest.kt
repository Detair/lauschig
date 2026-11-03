package io.wolftown.lauschig.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FontScaleBootTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun nothingStored_is100() = assertEquals(100, FontScaleBoot.read(context))

    @Test fun storedStep_isReadBack_unknownIs100() {
        FontScaleBoot.write(context, 130)
        assertEquals(130, FontScaleBoot.read(context))
        FontScaleBoot.write(context, 77)
        assertEquals(100, FontScaleBoot.read(context))
    }

    @Test fun override_multipliesTheSystemScale() {
        val system = Configuration().apply { fontScale = 1.3f }
        assertNull(FontScaleBoot.overrideFor(system, 100))
        assertEquals(1.3f * 1.15f, FontScaleBoot.overrideFor(system, 115)!!.fontScale, 0.0001f)
        assertEquals(0.85f, FontScaleBoot.overrideFor(Configuration().apply { fontScale = 1f }, 85)!!.fontScale, 0.0001f)
    }
}
