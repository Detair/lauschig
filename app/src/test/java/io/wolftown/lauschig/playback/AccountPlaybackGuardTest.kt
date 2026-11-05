package io.wolftown.lauschig.playback

import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 7, review I1: what the service does when the signed-in account changes around a title that just started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountPlaybackGuardTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val dev = DeviceLibrary.SERVER_ID
    private var title: Pair<String, String>? = null
    private var stops = 0
    private var forgotten = 0
    private var signedIn: String? = a
    private val guard = AccountPlaybackGuard({ title }, { stops++; title = null }, { forgotten++ }, { signedIn })

    @Test fun aTitleOfTheLeftAccount_stopsWhenTheAccountChanges() {
        guard.accountChanged(a)
        title = a to "a-book"
        guard.accountChanged(b)
        assertEquals(1, stops)
    }

    @Test fun aSignOut_stopsItToo() {
        guard.accountChanged(a)
        title = a to "a-book"
        guard.accountChanged(null)
        assertEquals(1, stops)
    }

    @Test fun aDeviceTitleStartedInTheGap_goesToo_theNextAccountStartsQuietly() {
        guard.accountChanged(a)
        title = dev to "local:x"
        guard.accountChanged(b)
        assertEquals(1, stops)
    }

    @Test fun theNewAccountsOwnTitle_staysPlaying() {
        guard.accountChanged(a)
        title = b to "b-book"
        guard.accountChanged(b)
        assertEquals(0, stops)
    }

    @Test fun theFirstAnswerAtTheStart_stopsNothing_aDeviceTitleMayAlreadyPlay() {
        title = dev to "local:x"
        guard.accountChanged(a)
        assertEquals(0, stops)
    }

    @Test fun signingIn_stopsNothing_theSameAccountAgain_neither() {
        guard.accountChanged(null)
        title = dev to "local:x"
        guard.accountChanged(a)
        assertEquals(0, stops)
        title = a to "a-book"
        guard.accountChanged(a)
        assertEquals(0, stops)
    }

    @Test fun onlyASwitch_forgetsTheTitleTheWidgetKeeps_aSignOutLeavesADeviceTitle() {
        guard.accountChanged(a)
        guard.accountChanged(a)
        assertEquals(0, forgotten)
        guard.accountChanged(b)
        assertEquals(1, forgotten)
        guard.accountChanged(null)
        assertEquals(1, forgotten)
        guard.accountChanged(a)
        assertEquals(1, forgotten)
    }

    /** Whatever path brought it (the queue's next title, a car start resolved just before the switch). */
    @Test fun aServerTitleThatLandsForAnotherAccount_stops() = runTest {
        title = a to "a-book"
        signedIn = b
        guard.titleChanged()
        assertEquals(1, stops)
    }

    @Test fun aTitleOfTheSignedInAccount_orADeviceTitle_orNone_plays() = runTest {
        title = a to "a-book"
        guard.titleChanged()
        title = dev to "local:x"
        signedIn = b
        guard.titleChanged()
        title = null
        guard.titleChanged()
        assertEquals(0, stops)
    }

    @Test fun aTitleThatChangedWhileTheAccountWasRead_isNotStopped() = runTest {
        val guard = AccountPlaybackGuard({ title }, { stops++ }, {}, { title = b to "b-book"; b })
        title = a to "a-book"
        guard.titleChanged()
        assertEquals(0, stops)
    }
}
