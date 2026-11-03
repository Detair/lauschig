package io.wolftown.lauschig.connection

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineOwnerTest {
    private val b = ServerAddresses(local = "http://192.168.1.20:13378".toHttpUrl(), public = "https://b.example/".toHttpUrl())

    @Test fun onlyAStateOfTheCurrentServersAddresses_counts() {
        // Right after an account switch the shared state may still replay server A's Online for a moment.
        assertTrue(ConnectionState.Online("https://b.example/".toHttpUrl(), ConnectionState.Via.PUBLIC).belongsTo(b))
        assertTrue(ConnectionState.Online("http://192.168.1.20:13378/".toHttpUrl(), ConnectionState.Via.LOCAL).belongsTo(b))
        assertFalse(ConnectionState.Online("https://a.example/".toHttpUrl(), ConnectionState.Via.PUBLIC).belongsTo(b))
        assertFalse(ConnectionState.Online("http://192.168.1.20:8080/".toHttpUrl(), ConnectionState.Via.LOCAL).belongsTo(b))
        assertFalse(ConnectionState.Online("https://b.example/".toHttpUrl(), ConnectionState.Via.PUBLIC).belongsTo(ServerAddresses(null, null)))
    }
}
