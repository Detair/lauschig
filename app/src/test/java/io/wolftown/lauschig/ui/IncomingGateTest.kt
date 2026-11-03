package io.wolftown.lauschig.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingGateTest {
    @Test fun signedOut_noNotificationOrLinkOpensAPageOverTheSignIn() {
        assertEquals(IncomingGate.WAIT, incomingGate(null))
        assertEquals(IncomingGate.DROP, incomingGate(false))
        assertEquals(IncomingGate.OPEN, incomingGate(true))
    }
}
