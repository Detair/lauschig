package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.Socket

/** The real TV end and the real phone end over 127.0.0.1; only the home-network check is swapped for the loopback. */
class HandoverRoundTripTest {
    private val handover = PhoneHandover(local = "http://192.168.1.20:13378", public = "https://abs.example.org", refresh = "refresh-token-123")
    private val sender = HandoverSender(HomeSockets { Socket() }, 2_000, 10_000) { LOOPBACK as Inet4Address }

    @Test fun thePhoneSignsInTheTv() = runBlocking {
        withTimeout(10_000) {
            val ready = CompletableDeferred<TvSetupCode>()
            val received = CompletableDeferred<PhoneHandover>()
            val tv = async(Dispatchers.Default) {
                HandoverListener().listen(LOOPBACK, { ready.complete(it) }) { received.complete(it); HandoverReply.SIGNED_IN }
            }
            assertEquals(SendResult.SIGNED_IN, sender.send(ready.await(), handover))
            assertEquals(ListenResult.Finished(HandoverReply.SIGNED_IN), tv.await())
            assertEquals(handover, received.await())
        }
    }

    @Test fun aReplacedCode_signsNothingIn() = runBlocking {
        withTimeout(10_000) {
            val oldReady = CompletableDeferred<TvSetupCode>()
            val old = async(Dispatchers.Default) { HandoverListener().listen(LOOPBACK, { oldReady.complete(it) }) { error("old code") } }
            val oldCode = oldReady.await()
            old.cancelAndJoin()

            val newReady = CompletableDeferred<TvSetupCode>()
            val new = async(Dispatchers.Default) { HandoverListener().listen(LOOPBACK, { newReady.complete(it) }) { error("not this packet") } }
            val newCode = newReady.await()

            assertEquals("the old port is closed", SendResult.NOT_SENT, sender.send(oldCode, handover))
            // Should the old port come back for the new code, the old key still does not open the packet.
            assertEquals(SendResult.NOT_ACCEPTED, sender.send(TvSetupCode(newCode.address, newCode.port, oldCode.publicKey), handover))
            new.cancelAndJoin()
        }
    }
}
