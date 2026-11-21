package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/** What the phone would send for [code]: `LTV1` and the sealed block. */
internal fun packet(code: TvSetupCode, handover: PhoneHandover = PhoneHandover(public = "https://abs.example.org", refresh = "r")): ByteArray =
    "LTV1".toByteArray(Charsets.US_ASCII) + HandoverCrypto.seal(code.publicKey, HandoverFormat.encode(handover)!!, code.contextInfo())

/** Connects to the listener, sends [bytes], and returns the one reply byte (-1: closed without one). */
internal fun exchange(code: TvSetupCode, bytes: ByteArray, endOutput: Boolean = false): Int = Socket().use { socket ->
    socket.connect(InetSocketAddress(LOOPBACK, code.port), 2_000)
    socket.soTimeout = 10_000
    socket.getOutputStream().write(bytes)
    if (endOutput) socket.shutdownOutput()
    socket.getInputStream().read()
}

/** The TV's end over 127.0.0.1. Limits are short so nothing waits on the real five minutes. */
class HandoverListenerTest {
    private val handover = PhoneHandover(local = "http://192.168.1.20:13378", refresh = "refresh-token-123")

    /** Starts [listen] and waits until its code is out. */
    private class Running(val result: kotlinx.coroutines.Deferred<ListenResult>, val code: TvSetupCode)

    private suspend fun kotlinx.coroutines.CoroutineScope.start(
        limits: HandoverLimits = HandoverLimits(openMs = 30_000, readMs = 2_000, rejected = 20),
        signIn: suspend (PhoneHandover) -> HandoverReply = { HandoverReply.SIGNED_IN },
    ): Running {
        val ready = CompletableDeferred<TvSetupCode>()
        val result = async(Dispatchers.Default) { HandoverListener(limits).listen(LOOPBACK, { ready.complete(it) }, signIn) }
        return Running(result, ready.await())
    }

    private fun refused(port: Int) {
        try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 2_000) }
            fail("port $port is still open")
        } catch (_: IOException) {
        }
    }

    @Test fun theCode_namesTheBoundAddressAndAFreshKey() = runBlocking {
        withTimeout(10_000) {
            val first = start()
            assertEquals("127.0.0.1", first.code.address)
            assertTrue(first.code.port > 0)
            val second = start()
            assertTrue(!first.code.publicKey.contentEquals(second.code.publicKey))
            first.result.cancelAndJoin()
            second.result.cancelAndJoin()
        }
    }

    @Test fun aValidPacket_signsInOnce_andRepliesWhatSignInSaid() = runBlocking {
        withTimeout(10_000) {
            val calls = AtomicInteger()
            val received = CompletableDeferred<PhoneHandover>()
            val running = start { calls.incrementAndGet(); received.complete(it); HandoverReply.SERVER_UNREACHABLE }
            assertEquals(3, exchange(running.code, packet(running.code, handover)))
            assertEquals(ListenResult.Finished(HandoverReply.SERVER_UNREACHABLE), running.result.await())
            assertEquals(1, calls.get())
            assertEquals(handover, received.await())
            refused(running.code.port)
        }
    }

    @Test fun afterAValidPacket_thePortIsClosed_evenWhileSigningIn() = runBlocking {
        withTimeout(10_000) {
            lateinit var code: TvSetupCode
            val running = start { refused(code.port); HandoverReply.SIGNED_IN }
            code = running.code
            assertEquals(1, exchange(code, packet(code, handover)))
            assertEquals(ListenResult.Finished(HandoverReply.SIGNED_IN), running.result.await())
        }
    }

    @Test fun garbageAndPacketsForAnotherKey_areNotAccepted_andTheCodeStaysOpen() = runBlocking {
        withTimeout(10_000) {
            val calls = AtomicInteger()
            val running = start { calls.incrementAndGet(); HandoverReply.SIGNED_IN }
            val code = running.code
            val otherKey = TvSetupCode(code.address, code.port, HandoverKey.generate().publicKey)
            val stale = TvSetupCode(code.address, code.port + 1, code.publicKey)
            assertEquals("zeros", 2, exchange(code, ByteArray(HandoverFormat.PACKET_SIZE)))
            assertEquals("wrong magic", 2, exchange(code, "LTV2".toByteArray() + packet(code).copyOfRange(4, HandoverFormat.PACKET_SIZE)))
            assertEquals("too short", 2, exchange(code, "LTV1".toByteArray(), endOutput = true))
            assertEquals("another key", 2, exchange(code, packet(otherKey)))
            assertEquals("another port in the context", 2, exchange(code, packet(stale)))
            assertEquals(0, calls.get())
            assertEquals(1, exchange(code, packet(code)))
            assertEquals(ListenResult.Finished(HandoverReply.SIGNED_IN), running.result.await())
        }
    }

    @Test fun aSealedBlockWithoutValidContent_isNotAccepted() = runBlocking {
        withTimeout(10_000) {
            val running = start()
            val code = running.code
            val json = """{"v":1,"public":"https://user:pass@abs.example.org","refresh":"r"}""".toByteArray()
            val block = ByteArray(HandoverFormat.PLAIN_SIZE).also { it[1] = json.size.toByte(); json.copyInto(it, 2) }
            assertEquals(2, exchange(code, "LTV1".toByteArray() + HandoverCrypto.seal(code.publicKey, block, code.contextInfo())))
            running.result.cancelAndJoin()
        }
    }

    @Test fun afterTooManyRejections_theCodeEnds() = runBlocking {
        withTimeout(10_000) {
            val running = start(HandoverLimits(openMs = 30_000, readMs = 2_000, rejected = 3))
            repeat(3) { assertEquals(2, exchange(running.code, ByteArray(HandoverFormat.PACKET_SIZE))) }
            assertEquals(ListenResult.TooManyAttempts, running.result.await())
            refused(running.code.port)
        }
    }

    @Test fun aSilentConnection_isDroppedAfterReadMs_andTheRealPhoneGetsThrough() = runBlocking {
        withTimeout(10_000) {
            val running = start(HandoverLimits(openMs = 30_000, readMs = 300, rejected = 20))
            val code = running.code
            Socket().use { silent ->
                silent.connect(InetSocketAddress(LOOPBACK, code.port), 2_000)
                // A trickle that never completes the packet must not stretch the deadline either.
                silent.getOutputStream().write("LTV1".toByteArray())
                assertEquals(1, exchange(code, packet(code, handover)))
                silent.soTimeout = 5_000
                assertEquals(2, silent.getInputStream().read())
            }
            assertEquals(ListenResult.Finished(HandoverReply.SIGNED_IN), running.result.await())
        }
    }

    @Test fun aTrickle_isCutOffAfterReadMsInTotal_notPerRead() = runBlocking {
        withTimeout(10_000) {
            val readMs = 600L
            val running = start(HandoverLimits(openMs = 30_000, readMs = readMs, rejected = 20))
            val code = running.code
            Socket().use { trickle ->
                trickle.connect(InetSocketAddress(LOOPBACK, code.port), 2_000)
                val started = System.nanoTime()
                // One byte every readMs / 3: each read gets data well within readMs, so only a deadline for the whole
                // connection ends it. 2100 bytes at this pace would take minutes.
                val writer = thread(isDaemon = true) {
                    try {
                        val output = trickle.getOutputStream()
                        while (true) {
                            output.write('L'.code)
                            Thread.sleep(readMs / 3)
                        }
                    } catch (_: Exception) {
                    }
                }
                // A per-read timeout would never fire, and this read would time out instead of seeing the reply.
                trickle.soTimeout = 5_000
                assertEquals(2, trickle.getInputStream().read())
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("cut off after $elapsedMs ms", elapsedMs < 3 * readMs)
                trickle.close()
                writer.join(2_000)
            }
            assertEquals(1, exchange(code, packet(code, handover)))
            assertEquals(ListenResult.Finished(HandoverReply.SIGNED_IN), running.result.await())
        }
    }

    @Test fun cancellingBetweenAcceptAndSignIn_endsThePhonesWaitAtOnce() = runBlocking {
        withTimeout(10_000) {
            val job = AtomicReference<Job>()
            val ready = CompletableDeferred<TvSetupCode>()
            val signIns = AtomicInteger()
            val listener = HandoverListener(HandoverLimits(openMs = 30_000, readMs = 2_000, rejected = 20)) { job.get().cancel() }
            val result = async(Dispatchers.Default) {
                listener.listen(LOOPBACK, { ready.complete(it) }) { signIns.incrementAndGet(); HandoverReply.SIGNED_IN }
            }
            job.set(result)
            val code = ready.await()
            // exchange waits up to 10 s; the reply must come long before, and it must say "failed".
            assertEquals(5, exchange(code, packet(code, handover)))
            result.join()
            assertTrue(result.isCancelled)
            assertEquals(0, signIns.get())
            refused(code.port)
        }
    }

    @Test fun withoutAValidPacket_theCodeExpires() = runBlocking {
        withTimeout(10_000) {
            val running = start(HandoverLimits(openMs = 200, readMs = 100, rejected = 20))
            assertEquals(ListenResult.Expired, running.result.await())
            refused(running.code.port)
        }
    }

    @Test fun aSignInThatThrows_repliesFailed() = runBlocking {
        withTimeout(10_000) {
            val running = start { throw IllegalStateException("boom") }
            assertEquals(5, exchange(running.code, packet(running.code)))
            assertEquals(ListenResult.Finished(HandoverReply.FAILED), running.result.await())
        }
    }

    @Test fun cancelling_closesThePortAtOnce() = runBlocking {
        withTimeout(10_000) {
            val running = start(HandoverLimits(openMs = 60_000, readMs = 60_000, rejected = 20))
            running.result.cancelAndJoin()
            refused(running.code.port)
        }
    }

    @Test fun cancelling_doesNotWaitForAReadDeadline() = runBlocking {
        withTimeout(10_000) {
            val running = start(HandoverLimits(openMs = 60_000, readMs = 60_000, rejected = 20))
            Socket().use { silent ->
                silent.connect(InetSocketAddress(LOOPBACK, running.code.port), 2_000)
                running.result.cancelAndJoin()
                silent.soTimeout = 5_000
                // Closed by the TV: either an orderly end or a reset, never the 5 s timeout.
                try {
                    assertEquals(-1, silent.getInputStream().read())
                } catch (e: java.net.SocketTimeoutException) {
                    throw AssertionError("the connection was left open", e)
                } catch (_: IOException) {
                }
            }
            refused(running.code.port)
        }
    }

    @Test fun cancellingRightAfterSignIn_stillGivesThePhoneItsReply() = runBlocking {
        withTimeout(10_000) {
            val job = CompletableDeferred<Job>()
            val running = start { job.await().cancel(); HandoverReply.SIGNED_IN }
            job.complete(running.result)
            assertEquals(1, exchange(running.code, packet(running.code)))
            running.result.join()
            assertTrue(running.result.isCancelled)
        }
    }

    @Test fun aPortThatCannotBeOpened_fails() = runBlocking {
        withTimeout(10_000) {
            // 192.0.2.1 (TEST-NET-1) is on no interface of the test machine, so binding to it fails.
            val result = HandoverListener(HandoverLimits()).listen(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), { fail("no code without a port") }) { HandoverReply.SIGNED_IN }
            assertEquals(ListenResult.Failed, result)
        }
    }

    /** Some devices, or a release build missing a class, cannot set up HPKE: the page must say "failed", not crash. */
    @Test fun aKeyThatCannotBeMade_fails_withoutAPort() = runBlocking {
        withTimeout(10_000) {
            for (problem in listOf(java.security.GeneralSecurityException("no HPKE"), IllegalStateException("no provider"))) {
                val listener = HandoverListener(HandoverLimits(), newKey = { throw problem }, afterAccept = {})
                val result = listener.listen(LOOPBACK, { fail("no code without a key") }) { HandoverReply.SIGNED_IN }
                assertEquals(problem.toString(), ListenResult.Failed, result)
            }
        }
    }
}
