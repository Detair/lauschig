package io.wolftown.lauschig.tv

import io.wolftown.lauschig.tv.nav.TvHome
import io.wolftown.lauschig.tv.nav.TvProfiles
import io.wolftown.lauschig.tv.nav.tvStart
import io.wolftown.lauschig.ui.nav.Library
import io.wolftown.lauschig.ui.nav.ProfilePicker
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Start
import io.wolftown.lauschig.ui.nav.Welcome
import org.junit.Assert.assertEquals
import org.junit.Test

class TvDestinationsTest {
    @Test fun theSignIn_staysTheSignIn() {
        assertEquals(Setup(), tvStart(Setup()))
        assertEquals(Setup.unlock("s"), tvStart(Setup.unlock("s")))
    }

    @Test fun theWelcome_isSkippedForTheSignIn() = assertEquals(Setup(), tvStart(Welcome))

    @Test fun theProfilePicker_becomesTheTvOne() = assertEquals(TvProfiles, tvStart(ProfilePicker))

    @Test fun everyStartTab_becomesTheTvHome() {
        assertEquals(TvHome, tvStart(Start))
        assertEquals(TvHome, tvStart(Library))
    }
}
