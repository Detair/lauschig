package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject

/** The fixed limits of one code; tests shorten them. */
data class HandoverLimits(val openMs: Long = 300_000, val readMs: Long = 5_000, val rejected: Int = 20)

sealed interface ListenResult {
    /** One packet was accepted; [reply] is what sign-in answered and what the phone was told. */
    data class Finished(val reply: HandoverReply) : ListenResult

    /** No valid packet within [HandoverLimits.openMs]. */
    data object Expired : ListenResult

    /** [HandoverLimits.rejected] connections were turned away. */
    data object TooManyAttempts : ListenResult

    /** The port could not be opened or broke. */
    data object Failed : ListenResult
}

/** The TV's end: one port, one key and at most one accepted packet per [listen]. */
class HandoverListener(private val limits: HandoverLimits) {
    @Inject constructor() : this(HandoverLimits())

    /**
     * One code's life: opens a port on [bind], calls [onReady] with the code (on an IO thread), and ends as
     * [ListenResult] says. Cancelling closes the port and any connection at once; the key is gone when this returns.
     */
    suspend fun listen(bind: InetAddress, onReady: (TvSetupCode) -> Unit, signIn: suspend (PhoneHandover) -> HandoverReply): ListenResult =
        withContext(Dispatchers.IO) {
            // The code carries a literal IPv4 address; anything else could not be scanned.
            if (bind !is Inet4Address) return@withContext ListenResult.Failed
            var key: HandoverKey? = HandoverKey.generate()
            val server = try {
                // Backlog 1: connections are taken one after the other anyway.
                ServerSocket(0, 1, bind)
            } catch (_: IOException) {
                return@withContext ListenResult.Failed
            }
            try {
                val code = TvSetupCode(bind.hostAddress.orEmpty(), server.localPort, key!!.publicKey)
                val outcome = closeOnCancel { sockets ->
                    sockets.add(server)
                    onReady(code)
                    acceptOne(server, sockets, key!!, code)
                }
                ensureActive()
                val accepted = when (outcome) {
                    is Outcome.Ended -> return@withContext outcome.result
                    is Outcome.Accepted -> outcome
                }
                // One packet per code: nobody else gets in, and the key cannot open a second one.
                server.close()
                key = null
                accepted.socket.use { socket ->
                    val reply = try {
                        signIn(accepted.handover)
                    } catch (_: Exception) {
                        // Also a cancellation: the phone still learns that it did not work, the cancellation goes on below.
                        HandoverReply.FAILED
                    }
                    answer(socket, reply)
                    ensureActive()
                    ListenResult.Finished(reply)
                }
            } finally {
                server.close()
            }
        }

    private sealed interface Outcome {
        data class Ended(val result: ListenResult) : Outcome
        class Accepted(val socket: Socket, val handover: PhoneHandover) : Outcome
    }

    /** Takes connections one by one until one carries a valid packet; blocking, ended early by [OpenSockets]. */
    private fun acceptOne(server: ServerSocket, sockets: OpenSockets, key: HandoverKey, code: TvSetupCode): Outcome {
        val openUntil = System.nanoTime() + limits.openMs * NANOS_PER_MS
        var rejected = 0
        while (true) {
            val left = millisUntil(openUntil)
            if (left <= 0) return Outcome.Ended(ListenResult.Expired)
            val client = try {
                // Inside the try: after cancellation the server is closed and even setting the timeout throws.
                server.soTimeout = left
                server.accept()
            } catch (_: SocketTimeoutException) {
                return Outcome.Ended(ListenResult.Expired)
            } catch (_: IOException) {
                // Also the way out after cancellation, whose result the caller drops.
                return Outcome.Ended(ListenResult.Failed)
            }
            sockets.add(client)
            // One deadline for the whole connection: a trickle of bytes must not keep the port busy.
            val readUntil = minOf(openUntil, System.nanoTime() + limits.readMs * NANOS_PER_MS)
            val handover = read(client, readUntil, key, code)
            if (handover != null) {
                sockets.remove(client)
                server.close()
                return Outcome.Accepted(client, handover)
            }
            try {
                client.getOutputStream().write(HandoverReply.NOT_ACCEPTED.code)
            } catch (_: IOException) {
            }
            sockets.remove(client)
            client.close()
            if (sockets.cancelled) return Outcome.Ended(ListenResult.Failed)
            rejected++
            if (rejected >= limits.rejected) return Outcome.Ended(ListenResult.TooManyAttempts)
        }
    }

    /** The handover in a full, valid packet read before [until]; null for anything else. */
    private fun read(client: Socket, until: Long, key: HandoverKey, code: TvSetupCode): PhoneHandover? {
        val packet = ByteArray(HandoverFormat.PACKET_SIZE)
        var count = 0
        try {
            val input = client.getInputStream()
            while (count < packet.size) {
                val left = millisUntil(until)
                if (left <= 0) return null
                client.soTimeout = left
                val read = input.read(packet, count, packet.size - count)
                if (read < 0) return null
                count += read
            }
        } catch (_: IOException) {
            return null
        }
        for (i in MAGIC.indices) if (packet[i] != MAGIC[i]) return null
        val plain = key.open(packet.copyOfRange(MAGIC.size, packet.size), code.contextInfo()) ?: return null
        return HandoverFormat.decode(plain)
    }

    /**
     * Writes [reply] even when the caller is being cancelled: the TV leaves the page right after a sign-in, and the
     * phone must still learn that it worked. Bounded, so a stuck peer cannot hold the TV.
     */
    private suspend fun answer(socket: Socket, reply: HandoverReply) = withContext(NonCancellable) {
        withTimeoutOrNull(ANSWER_MS) {
            closeOnCancel { sockets ->
                sockets.add(socket)
                try {
                    socket.getOutputStream().apply {
                        write(reply.code)
                        flush()
                    }
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun millisUntil(deadline: Long): Int =
        ((deadline - System.nanoTime() + NANOS_PER_MS - 1) / NANOS_PER_MS).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    internal companion object {
        val MAGIC = "LTV1".toByteArray(Charsets.US_ASCII)
        private const val NANOS_PER_MS = 1_000_000L
        private const val ANSWER_MS = 2_000L
    }
}
