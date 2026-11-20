package io.wolftown.lauschig.tv.setup

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.tvsetup.HandoverReply
import io.wolftown.lauschig.tvsetup.HomeNetwork
import io.wolftown.lauschig.tvsetup.ListenResult
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.tvsetup.TvSetupCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

private class FakeHomeNetwork(var address: Inet4Address? = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)) as Inet4Address) : HomeNetwork {
    override fun ownAddress(): Inet4Address? = address
    override fun open(): Socket? = null
}

/** Stands in for [io.wolftown.lauschig.tvsetup.HandoverListener.listen]: each call is one run the test drives. */
private class FakeListener {
    inner class Run(val bind: InetAddress, val onReady: (TvSetupCode) -> Unit, val signIn: suspend (PhoneHandover) -> HandoverReply) {
        val result = CompletableDeferred<ListenResult>()
        var cancelled = false
    }

    val runs = mutableListOf<Run>()

    suspend fun listen(bind: InetAddress, onReady: (TvSetupCode) -> Unit, signIn: suspend (PhoneHandover) -> HandoverReply): ListenResult {
        val run = Run(bind, onReady, signIn).also { runs += it }
        return try {
            run.result.await()
        } catch (e: CancellationException) {
            run.cancelled = true
            throw e
        }
    }
}

private fun code(port: Int) = TvSetupCode("192.168.1.50", port, ByteArray(32) { it.toByte() })

@OptIn(ExperimentalCoroutinesApi::class)
class TvPhoneSetupViewModelTest {
    private val network = FakeHomeNetwork()
    private val listener = FakeListener()
    private val viewModels = TestViewModels()
    private val handedOver = mutableListOf<PhoneHandover>()
    private var reply = HandoverReply.SIGNED_IN
    private val signIn: suspend (PhoneHandover) -> HandoverReply = { handedOver += it; reply }

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(TvPhoneSetupViewModel(network, listener::listen))

    @Test fun withoutAHomeAddress_noHomeNetwork_andNoPort() {
        network.address = null
        val v = vm()
        v.show(signIn)
        assertEquals(TvPhoneSetupState.NoHomeNetwork, v.state.value)
        assertTrue(listener.runs.isEmpty())
    }

    @Test fun startsOnTheHomeAddress_thenWaitsWithCodeAndNumber() {
        val v = vm()
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
        v.show(signIn)
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
        assertEquals(network.address, listener.runs.single().bind)
        val c = code(41234)
        listener.runs.single().onReady(c)
        assertEquals(TvPhoneSetupState.Waiting(c.text, c.number), v.state.value)
    }

    @Test fun aPacket_signsIn_throughThePagesSignIn() = runBlocking {
        val v = vm()
        v.show(signIn)
        val run = listener.runs.single()
        run.onReady(code(41234))
        val handover = PhoneHandover(public = "https://abs.example.org/", refresh = "r")
        var during: TvPhoneSetupState? = null
        reply = HandoverReply.SIGNED_IN
        val answer = run.signIn(handover).also { during = v.state.value }
        assertEquals(HandoverReply.SIGNED_IN, answer)
        assertEquals(TvPhoneSetupState.SigningIn, during)
        assertSame(handover, handedOver.single())
    }

    @Test fun everyEnd_hasItsState() {
        val expected = listOf(
            ListenResult.Finished(HandoverReply.SIGNED_IN) to TvPhoneSetupState.SigningIn,
            ListenResult.Finished(HandoverReply.SERVER_UNREACHABLE) to TvPhoneSetupState.Stopped(TvPhoneSetupStop.SERVER_UNREACHABLE),
            ListenResult.Finished(HandoverReply.SIGN_IN_REFUSED) to TvPhoneSetupState.Stopped(TvPhoneSetupStop.SIGN_IN_REFUSED),
            ListenResult.Finished(HandoverReply.FAILED) to TvPhoneSetupState.Stopped(TvPhoneSetupStop.FAILED),
            ListenResult.Finished(HandoverReply.NOT_ACCEPTED) to TvPhoneSetupState.Stopped(TvPhoneSetupStop.FAILED),
            ListenResult.Expired to TvPhoneSetupState.Stopped(TvPhoneSetupStop.EXPIRED),
            ListenResult.TooManyAttempts to TvPhoneSetupState.Stopped(TvPhoneSetupStop.TOO_MANY_ATTEMPTS),
            ListenResult.Failed to TvPhoneSetupState.Stopped(TvPhoneSetupStop.FAILED),
        )
        for ((result, state) in expected) {
            val v = vm()
            v.show(signIn)
            listener.runs.last().onReady(code(41234))
            listener.runs.last().result.complete(result)
            assertEquals("$result", state, v.state.value)
        }
    }

    @Test fun hide_endsTheListener_andTheCodeGoes() {
        val v = vm()
        v.show(signIn)
        listener.runs.single().onReady(code(41234))
        v.hide()
        assertTrue(listener.runs.single().cancelled)
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
    }

    @Test fun aLateReportOfAnEndedRun_bringsNothingBack() {
        val v = vm()
        v.show(signIn)
        val old = listener.runs.single()
        v.hide()
        // The listener's IO thread may still report after the cancellation.
        old.onReady(code(41234))
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
    }

    @Test fun comingBack_givesANewCode() {
        val v = vm()
        v.show(signIn)
        listener.runs[0].onReady(code(41234))
        val first = v.state.value
        v.hide()
        v.show(signIn)
        assertEquals(2, listener.runs.size)
        listener.runs[1].onReady(code(41235))
        assertNotEquals(first, v.state.value)
    }

    @Test fun newCode_endsTheOldOne_andStartsAnother() {
        val v = vm()
        v.show(signIn)
        listener.runs[0].onReady(code(41234))
        val first = v.state.value
        v.newCode()
        assertTrue(listener.runs[0].cancelled)
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
        listener.runs[1].onReady(code(41235))
        assertTrue(v.state.value is TvPhoneSetupState.Waiting)
        assertNotEquals(first, v.state.value)
    }

    @Test fun newCode_afterAStop_startsAgain() {
        val v = vm()
        v.show(signIn)
        listener.runs[0].result.complete(ListenResult.Expired)
        v.newCode()
        assertEquals(2, listener.runs.size)
        assertFalse(listener.runs[1].cancelled)
    }

    @Test fun newCode_whileHidden_opensNothing() {
        val v = vm()
        v.newCode()
        assertTrue(listener.runs.isEmpty())
    }

    @Test fun showTwice_oneListener() {
        val v = vm()
        v.show(signIn)
        v.show(signIn)
        assertEquals(1, listener.runs.size)
    }

    @Test fun clearingTheViewModel_endsTheListener() {
        val v = vm()
        v.show(signIn)
        viewModels.clear()
        assertTrue(listener.runs.single().cancelled)
        assertEquals(TvPhoneSetupState.Starting, v.state.value)
    }

    @Test fun theWaitingState_keepsTheCodeOutOfItsText() {
        val c = code(41234)
        val text = TvPhoneSetupState.Waiting(c.text, c.number).toString()
        assertFalse(text.contains("192.168.1.50"))
        assertFalse(text.contains(c.number))
    }
}
