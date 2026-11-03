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

    @Test fun recreateGuard_onlyForADifferentValue_andTheCacheHoldsItFirst() {
        FontScaleBoot.write(context, 100)
        assertEquals(false, FontScaleBoot.needsRecreate(context, applied = 115, wanted = 115))
        assertEquals(100, FontScaleBoot.read(context)) // nothing written for an unchanged value
        assertEquals(true, FontScaleBoot.needsRecreate(context, applied = 100, wanted = 130))
        // The recreated activity reads 130 in attachBaseContext, so it does not recreate again.
        assertEquals(130, FontScaleBoot.read(context))
        assertEquals(false, FontScaleBoot.needsRecreate(context, applied = FontScaleBoot.read(context), wanted = 130))
    }
}
