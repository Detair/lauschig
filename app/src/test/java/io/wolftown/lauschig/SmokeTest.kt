package io.wolftown.lauschig

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun appIdIsCorrect() {
        assertEquals("io.wolftown.lauschig", BuildConfig.APPLICATION_ID)
    }
}
