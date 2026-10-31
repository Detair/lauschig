package io.wolftown.lauschig.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientTokenCipherTest {
    private class Plain : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    @Test
    fun cipherKeyLoss_regeneratesAndAllowsNewSave() {
        var resets = 0
        var creates = 0
        val c = ResilientTokenCipher(
            create = { creates++; if (resets == 0) error("master key lost") else Plain() },
            reset = { resets++ },
        )
        assertEquals("enc:tok", c.encrypt("tok"))
        assertEquals(1, resets)
        assertEquals("tok", c.decrypt("enc:tok"))
    }

    @Test
    fun keyInvalidatedAtUse_resetsOnceAndRetries() {
        var resets = 0
        var broken = true
        val c = ResilientTokenCipher(
            create = { object : TokenCipher {
                val ok = !broken
                override fun encrypt(plain: String) = if (ok) "enc:$plain" else error("invalidated")
                override fun decrypt(encrypted: String) = null
            } },
            reset = { resets++; broken = false },
        )
        assertEquals("enc:x", c.encrypt("x"))
        assertTrue(resets == 1)
    }
}
