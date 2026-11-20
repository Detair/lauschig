package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

enum class SendResult { SIGNED_IN, NOT_ACCEPTED, SERVER_UNREACHABLE, SIGN_IN_REFUSED, FAILED, NO_HOME_NETWORK, NOT_SENT, NO_REPLY }

/** An unconnected socket of a Wi-Fi or Ethernet network that is no VPN; null: there is none. */
fun interface HomeSockets {
    fun open(): Socket?
}

/** The phone's end: one packet to the TV the code names, one byte back. */
class HandoverSender internal constructor(
    private val sockets: HomeSockets,
    private val connectMs: Int,
    private val replyMs: Int,
    // Tests on the loopback swap this; the app always uses the home-network rule.
    private val homeAddress: (String) -> Inet4Address?,
) {
    constructor(sockets: HomeSockets, connectMs: Int = 5_000, replyMs: Int = 30_000) :
        this(sockets, connectMs, replyMs, TvSetupCodes::homeAddress)

    suspend fun send(code: TvSetupCode, handover: PhoneHandover): SendResult = withContext(Dispatchers.IO) {
        // Checked again here: whatever built the code, the token goes to the home network or nowhere.
        val address = homeAddress(code.address) ?: return@withContext SendResult.NOT_SENT
        val plain = HandoverFormat.encode(handover) ?: return@withContext SendResult.NOT_SENT
        val packet = try {
            HandoverListener.MAGIC + HandoverCrypto.seal(code.publicKey, plain, code.contextInfo())
        } catch (_: java.security.GeneralSecurityException) {
            return@withContext SendResult.NOT_SENT
        }
        val socket = sockets.open() ?: return@withContext SendResult.NO_HOME_NETWORK
        val result = socket.use {
            closeOnCancel { open ->
                open.add(socket)
                exchange(socket, address, code.port, packet)
            }
        }
        ensureActive()
        result
    }

    private fun exchange(socket: Socket, address: Inet4Address, port: Int, packet: ByteArray): SendResult {
        try {
            // A literal address: connecting never asks a name server.
            socket.connect(InetSocketAddress(address, port), connectMs)
            socket.getOutputStream().apply {
                write(packet)
                flush()
            }
        } catch (_: IOException) {
            return SendResult.NOT_SENT
        } catch (_: IllegalArgumentException) {
            // A port outside 0..65535 in a code built by hand.
            return SendResult.NOT_SENT
        }
        val reply = try {
            socket.soTimeout = replyMs
            socket.getInputStream().read()
        } catch (_: IOException) {
            return SendResult.NO_REPLY
        }
        return when (HandoverReply.of(reply)) {
            HandoverReply.SIGNED_IN -> SendResult.SIGNED_IN
            HandoverReply.NOT_ACCEPTED -> SendResult.NOT_ACCEPTED
            HandoverReply.SERVER_UNREACHABLE -> SendResult.SERVER_UNREACHABLE
            HandoverReply.SIGN_IN_REFUSED -> SendResult.SIGN_IN_REFUSED
            HandoverReply.FAILED -> SendResult.FAILED
            null -> SendResult.NO_REPLY
        }
    }
}
