package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The phone's end against a plain socket that plays the TV. */
class HandoverSenderTest {
    private val handover = PhoneHandover(public = "https://abs.example.org", refresh = "refresh-token-123")
    private val key = HandoverKey.generate()

    /** Counts the sockets the sender asks for, so a test can tell that nothing left the phone. */
    private class CountingSockets(private val available: Boolean = true) : HomeSockets {
        val opened = AtomicInteger()
        override fun open(): Socket? = if (available) Socket().also { opened.incrementAndGet() } else null
    }

    private fun loopbackSender(sockets: HomeSockets, replyMs: Int = 30_000) =
        HandoverSender(sockets, 2_000, replyMs) { LOOPBACK as Inet4Address }

    /** A fake TV on 127.0.0.1 that reads the whole packet and then does [then] with the connection. */
    private fun fakeTv(then: (Socket, ByteArray) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, LOOPBACK)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val bytes = ByteArray(HandoverFormat.PACKET_SIZE)
                    DataInputStream(socket.getInputStream()).readFully(bytes)
                    then(socket, bytes)
                }
            }
            server.close()
        }
        return server
    }

    @Test fun anAddressOutsideTheHomeNetwork_sendsNothing() = runBlocking {
        listOf("127.0.0.1", "8.8.8.8", "172.32.0.1", "tv.example.org").forEach { address ->
            val sockets = CountingSockets()
            assertEquals(address, SendResult.NOT_SENT, HandoverSender(sockets).send(TvSetupCode(address, 41234, key.publicKey), handover))
            assertEquals(address, 0, sockets.opened.get())
        }
    }

    @Test fun withoutAHomeNetworkSocket_nothingIsSent() = runBlocking {
        assertEquals(SendResult.NO_HOME_NETWORK, HandoverSender(CountingSockets(available = false)).send(TvSetupCode("192.168.1.50", 41234, key.publicKey), handover))
    }

    @Test fun contentThatDoesNotFit_isNotSent() = runBlocking {
        val sockets = CountingSockets()
        val tooBig = PhoneHandover(public = "https://abs.example.org", refresh = "x".repeat(HandoverFormat.PLAIN_SIZE))
        assertEquals(SendResult.NOT_SENT, HandoverSender(sockets).send(TvSetupCode("192.168.1.50", 41234, key.publicKey), tooBig))
        assertEquals(0, sockets.opened.get())
    }

    @Test fun nobodyListening_isNotSent() = runBlocking {
        val port = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        assertEquals(SendResult.NOT_SENT, loopbackSender(CountingSockets()).send(TvSetupCode("127.0.0.1", port, key.publicKey), handover))
    }

    @Test fun theTvClosingWithoutAReply_isNoReply() = runBlocking {
        val server = fakeTv { _, _ -> }
        assertEquals(SendResult.NO_REPLY, loopbackSender(CountingSockets()).send(TvSetupCode("127.0.0.1", server.localPort, key.publicKey), handover))
    }

    @Test fun anUnknownReply_isNoReply() = runBlocking {
        val server = fakeTv { socket, _ -> socket.getOutputStream().write(9) }
        assertEquals(SendResult.NO_REPLY, loopbackSender(CountingSockets()).send(TvSetupCode("127.0.0.1", server.localPort, key.publicKey), handover))
    }

    @Test fun noReplyInTime_isNoReply() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val server = fakeTv { _, _ -> runBlocking { done.await() } }
        assertEquals(SendResult.NO_REPLY, loopbackSender(CountingSockets(), replyMs = 200).send(TvSetupCode("127.0.0.1", server.localPort, key.publicKey), handover))
        done.complete(Unit)
        Unit
    }

    @Test fun eachReply_isPassedOn() = runBlocking {
        mapOf(
            1 to SendResult.SIGNED_IN, 2 to SendResult.NOT_ACCEPTED, 3 to SendResult.SERVER_UNREACHABLE,
            4 to SendResult.SIGN_IN_REFUSED, 5 to SendResult.FAILED,
        ).forEach { (reply, expected) ->
            val server = fakeTv { socket, _ -> socket.getOutputStream().write(reply) }
            assertEquals(reply.toString(), expected, loopbackSender(CountingSockets()).send(TvSetupCode("127.0.0.1", server.localPort, key.publicKey), handover))
        }
    }

    @Test fun thePacket_isWhatTheProtocolSays() = runBlocking {
        val received = CompletableDeferred<ByteArray>()
        val server = fakeTv { socket, bytes -> received.complete(bytes); socket.getOutputStream().write(1) }
        val target = TvSetupCode("127.0.0.1", server.localPort, key.publicKey)
        assertEquals(SendResult.SIGNED_IN, loopbackSender(CountingSockets()).send(target, handover))
        val bytes = received.await()
        assertEquals(HandoverFormat.PACKET_SIZE, bytes.size)
        assertEquals("LTV1", String(bytes, 0, 4, Charsets.US_ASCII))
        val plain = key.open(bytes.copyOfRange(4, bytes.size), target.contextInfo())!!
        assertEquals(handover, HandoverFormat.decode(plain))
        // Sealed for this very port: the same block does not open under another one.
        assertEquals(null, key.open(bytes.copyOfRange(4, bytes.size), TvSetupCode("127.0.0.1", server.localPort + 1, key.publicKey).contextInfo()))
    }

    @Test fun cancelling_closesTheSocket() = runBlocking {
        withTimeout(10_000) {
            val packetIn = CompletableDeferred<Unit>()
            val closedByPhone = CompletableDeferred<Int>()
            val server = fakeTv { socket, _ ->
                packetIn.complete(Unit)
                closedByPhone.complete(runCatching { socket.getInputStream().read() }.getOrDefault(-1))
            }
            val sending = async(Dispatchers.Default) {
                loopbackSender(CountingSockets()).send(TvSetupCode("127.0.0.1", server.localPort, key.publicKey), handover)
            }
            packetIn.await()
            sending.cancelAndJoin()
            assertEquals(-1, closedByPhone.await())
        }
    }
}
