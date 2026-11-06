package io.wolftown.lauschig.account

import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Base64

/** The store of Task 3 (3.1, 3.5): what is kept, how it is kept, and what the wrong entries cost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileLocksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private var clock = 1_000_000L

    /** Readable for the test (the plain text is what the cipher was given), but never the plain text itself in the file. */
    private inner class TestCipher : TokenCipher {
        val given = mutableListOf<String>()
        var broken = false
        var throwing = false
        override fun encrypt(plain: String): String {
            given += plain
            return "enc:" + Base64.getEncoder().encodeToString(plain.toByteArray())
        }
        override fun decrypt(encrypted: String): String? {
            if (throwing) throw IllegalStateException("provider")
            if (broken) return null
            return String(Base64.getDecoder().decode(encrypted.removePrefix("enc:")))
        }
    }

    private val cipher = TestCipher()
    private val file by lazy { tmp.root.resolve("locks.preferences_pb") }
    private val data by lazy { PreferenceDataStoreFactory.create(corruptionHandler = damagedLocksHandler, scope = scope) { file } }

    private fun locks(iterations: Int = 1_000, derive: ((CharArray, ByteArray, Int) -> ByteArray)? = null) =
        if (derive == null) DataStoreProfileLocks(data, cipher, { clock }, iterations)
        else DataStoreProfileLocks(data, cipher, { clock }, iterations, derive)

    @After fun tearDown() {
        Log.recorder = { _, _, _, _ -> }
        scope.cancel()
    }

    private fun wrongTimes(locks: ProfileLocks, n: Int) = runBlocking {
        repeat(n) { locks.verifyPin(a, "9999") }
    }

    @Test fun withoutAnything_noProtection() = runBlocking {
        assertEquals(ProfileLockKind.NONE, locks().kind(a))
        assertEquals(emptyMap<String, ProfileLockKind>(), locks().kinds.first())
    }

    @Test fun aPin_isRight_orWrong_perProfile() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        locks.setDevice(b)
        assertEquals(ProfileLockKind.PIN, locks.kind(a))
        assertEquals(ProfileLockKind.DEVICE, locks.kind(b))
        assertEquals(mapOf(a to ProfileLockKind.PIN, b to ProfileLockKind.DEVICE), locks.kinds.first())
        assertEquals(PinCheck.Correct, locks.verifyPin(a, "1234"))
        assertEquals(PinCheck.Wrong(4), locks.verifyPin(a, "4321"))
        assertEquals("a device lock has no PIN", PinCheck.NoPin, locks.verifyPin(b, "1234"))
    }

    @Test fun theRealHash_acceptsTheRightPin_only() = runBlocking {
        val locks = DataStoreProfileLocks(data, cipher, { clock }, 1_000)
        locks.setPin(a, "12345678")
        assertEquals(PinCheck.Correct, locks.verifyPin(a, "12345678"))
        assertEquals(PinCheck.Wrong(4), locks.verifyPin(a, "12345679"))
    }

    @Test fun everyPinHasItsOwnSalt_andTheHashIsNotThePin() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        locks.setPin(b, "1234")
        val entries = cipher.given.toList()
        assertEquals(2, entries.size)
        assertNotEquals(entries[0], entries[1])
        entries.forEach { assertFalse(it, "1234" in it) }
    }

    /** 3.1: on disk only what the cipher made of it; not even which kind of protection it is. */
    @Test fun onDisk_everythingIsEncrypted() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        locks.setDevice(b)
        val values = data.data.first().asMap().values
        assertEquals(2, values.size)
        values.forEach { assertTrue("$it", (it as String).startsWith("enc:")) }
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("1234", "PIN", "DEVICE", "salt", "hash")) assertFalse(secret, secret in raw)
    }

    /** 3.1: the count is at least what the plan says; each hash keeps its own, so a later change locks nobody out. */
    @Test fun theCountStaysWithTheHash() = runBlocking {
        assertTrue(PIN_ITERATIONS >= 120_000)
        locks(iterations = 2_000).setPin(a, "1234")
        val seen = mutableListOf<Int>()
        val later = locks(iterations = 1_000, derive = { pin, salt, rounds ->
            seen += rounds
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec(pin, salt, rounds, 256)).encoded
        })
        assertEquals(PinCheck.Correct, later.verifyPin(a, "1234"))
        assertEquals(listOf(2_000), seen)
    }

    @Test fun theHashLogsItsCountAndTime_andNothingSecretGoesAnywhere() = runBlocking {
        ShadowLog.clear()
        val recorded = mutableListOf<String>()
        Log.recorder = { _, tag, msg, tr -> recorded += "$tag $msg ${tr?.message}" }
        val locks = DataStoreProfileLocks(data, cipher, { clock }, 1_000)
        locks.setPin(a, "73519")
        locks.verifyPin(a, "73519")
        locks.verifyPin(a, "86420")
        assertEquals(recorded.toString(), 3, recorded.size)
        recorded.forEach { assertTrue(it, Regex("""ProfileLocks hash: 1000 iterations, \d+ ms null""").matches(it)) }
        val entry = cipher.given.first()
        val secrets = Regex("\"(salt|hash)\":\"([^\"]+)\"").findAll(entry).map { it.groupValues[2] }.toList()
        assertEquals(2, secrets.size)
        val everything = recorded + ShadowLog.getLogs().map { "${it.tag} ${it.msg} ${it.throwable?.message}" }
        for (secret in secrets + listOf("73519", "86420")) everything.forEach { assertFalse(it, secret in it) }
        assertEquals("no secret in what a check answers", "Wrong(triesBeforeWait=3)", PinCheck.Wrong(3).toString())
    }

    @Test fun aPinIsFourToEightDigits_elseNothingIsStored() = runBlocking {
        val locks = locks()
        for (bad in listOf("", "123", "123456789", "12a4", "١٢٣٤", "12 34")) {
            assertFalse(bad, isValidPin(bad))
            try {
                locks.setPin(a, bad)
                fail("accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(ProfileLockKind.NONE, locks.kind(a))
        for (good in listOf("1234", "12345678", "0000")) assertTrue(good, isValidPin(good))
    }

    @Test fun theWaits_thirtySeconds_thenDoubling_upToFifteenMinutes() {
        assertEquals(listOf(0L, 0L, 0L, 0L), (1..4).map(::pinWaitMs))
        assertEquals(
            listOf(30L, 60L, 120L, 240L, 480L, 900L, 900L).map { it * 1_000 },
            (5..11).map(::pinWaitMs),
        )
        assertEquals(900_000L, pinWaitMs(1_000))
    }

    @Test fun fiveWrong_thenAWait_inWhichEvenTheRightPinDoesNothing() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        assertEquals(listOf(4, 3, 2, 1), (1..4).map { (locks.verifyPin(a, "9999") as PinCheck.Wrong).triesBeforeWait })
        assertEquals(0L, locks.waitRemainingMs(a))
        assertEquals(PinCheck.Wait(30_000, started = true), locks.verifyPin(a, "9999"))
        clock += 10_000
        assertEquals(20_000L, locks.waitRemainingMs(a))
        assertEquals("the right one is not even looked at", PinCheck.Wait(20_000, started = false), locks.verifyPin(a, "1234"))
        clock += 20_000
        assertEquals(0L, locks.waitRemainingMs(a))
        assertEquals("the next wrong one doubles", PinCheck.Wait(60_000, started = true), locks.verifyPin(a, "9999"))
        clock += 60_000
        assertEquals(PinCheck.Correct, locks.verifyPin(a, "1234"))
        assertEquals("the right one starts over", PinCheck.Wrong(4), locks.verifyPin(a, "9999"))
    }

    @Test fun aWait_isPerProfile() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        locks.setPin(b, "1234")
        wrongTimes(locks, 5)
        assertEquals(30_000L, locks.waitRemainingMs(a))
        assertEquals(0L, locks.waitRemainingMs(b))
        assertEquals(PinCheck.Correct, locks.verifyPin(b, "1234"))
    }

    /** Review focus 3: the count survives a new process. */
    @Test fun aNewProcess_keepsTheCountAndTheWait() = runBlocking {
        locks().setPin(a, "1234")
        wrongTimes(locks(), 3)
        assertEquals(PinCheck.Wrong(1), locks().verifyPin(a, "9999"))
        assertEquals(PinCheck.Wait(30_000, started = true), locks().verifyPin(a, "9999"))
        clock += 5_000
        assertEquals(25_000L, locks().waitRemainingMs(a))
        assertEquals(PinCheck.Wait(25_000, started = false), locks().verifyPin(a, "1234"))
    }

    /** Review focus 3: killing the process while it checks (in the time the wrong answer would take to be written) is no free try. */
    @Test fun aProcessDeathDuringTheCheck_stillCounts() {
        class ProcessDeath : Error()
        runBlocking { locks().setPin(a, "1234") }
        val dying = locks(derive = { _, _, _ -> throw ProcessDeath() })
        repeat(5) {
            try {
                runBlocking { dying.verifyPin(a, "9999") }
                fail("the check should have died")
            } catch (_: ProcessDeath) {
            }
        }
        runBlocking {
            assertEquals("the fifth try already started the wait", 30_000L, locks().waitRemainingMs(a))
            assertEquals(PinCheck.Wait(30_000, started = false), locks().verifyPin(a, "1234"))
        }
    }

    /**
     * Review focus 3: the wait is not on the clock the person can set. The store is given nothing else than the time since
     * boot; on the real one here, which only moves when the device's uptime does.
     */
    @Test fun settingTheClockBack_doesNotShortenTheWait_itIsNotTheClockItRunsOn() = runBlocking {
        val real = DataStoreProfileLocks(data, cipher, iterations = 1_000)
        real.setPin(a, "1234")
        repeat(5) { real.verifyPin(a, "9999") }
        assertEquals(30_000L, real.waitRemainingMs(a))
        // The wall clock of this JVM keeps running on its own here, the uptime stands still: the wait does too.
        Thread.sleep(50)
        assertEquals(30_000L, real.waitRemainingMs(a))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(29))
        assertTrue(real.waitRemainingMs(a) in 1..1_000L)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals(0L, real.waitRemainingMs(a))
    }

    /** After a reboot the time since boot starts again: only what has certainly passed counts, the wait never gets shorter. */
    @Test fun aReboot_doesNotShortenTheWait() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        wrongTimes(locks, 5)
        clock = 2_000 // the device restarted two seconds ago
        assertEquals(28_000L, locks().waitRemainingMs(a))
        assertEquals(PinCheck.Wait(28_000, started = false), locks().verifyPin(a, "1234"))
        clock = 1_000_000 + 5_000 // a long time up: what it says about the wait is less than has really passed
        assertEquals(25_000L, locks().waitRemainingMs(a))
    }

    /** Two taps in one moment are two tries. */
    @Test fun checksInParallel_allCount() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        (1..5).map { async(Dispatchers.Default) { locks.verifyPin(a, "9999") } }.awaitAll()
        assertEquals(30_000L, locks.waitRemainingMs(a))
    }

    /** The key is lost, or the data came from another device: never open. */
    @Test fun whatCannotBeRead_isProtected_notOpen() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        cipher.broken = true
        assertEquals(ProfileLockKind.UNREADABLE, locks.kind(a))
        assertEquals(mapOf(a to ProfileLockKind.UNREADABLE), locks.kinds.first())
        assertEquals(PinCheck.NoPin, locks.verifyPin(a, "1234"))
        cipher.broken = false
        cipher.throwing = true
        assertEquals(ProfileLockKind.UNREADABLE, locks.kind(a))
        cipher.throwing = false
        assertEquals("nothing lost once it can be read", PinCheck.Correct, locks.verifyPin(a, "1234"))
    }

    @Test fun anEntryOfALaterVersion_isProtected_notOpen() = runBlocking {
        val key = androidx.datastore.preferences.core.stringPreferencesKey("lock_$a")
        data.updateData { p: Preferences -> p.toMutablePreferences().apply { this[key] = cipher.encrypt("""{"kind":"FACE"}""") } }
        assertEquals(ProfileLockKind.UNREADABLE, locks().kind(a))
        val other = androidx.datastore.preferences.core.stringPreferencesKey("lock_$b")
        data.updateData { p: Preferences -> p.toMutablePreferences().apply { this[other] = cipher.encrypt("not json") } }
        assertEquals(ProfileLockKind.UNREADABLE, locks().kind(b))
    }

    /** A damaged file must not take the protection with it: everybody asks for a sign-in, until that took place. */
    @Test fun aDamagedFile_protectsEverybody_untilProven() = runBlocking {
        file.writeBytes(ByteArray(64) { 0x7f })
        val locks = locks()
        assertEquals(ProfileLockKind.UNREADABLE, locks.kind(a))
        assertEquals(ProfileLockKind.UNREADABLE, locks.kind(b))
        locks.remove(a)
        assertEquals(ProfileLockKind.NONE, locks.kind(a))
        assertEquals(ProfileLockKind.UNREADABLE, locks.kind(b))
        locks.setPin(b, "1234")
        assertEquals(PinCheck.Correct, locks.verifyPin(b, "1234"))
    }

    @Test fun removing_takesEverythingAlong_alsoTheCount() = runBlocking {
        val locks = locks()
        locks.setPin(a, "1234")
        wrongTimes(locks, 5)
        locks.remove(a)
        assertEquals(ProfileLockKind.NONE, locks.kind(a))
        assertEquals(0L, locks.waitRemainingMs(a))
        assertTrue(data.data.first().asMap().isEmpty())
        locks.setPin(a, "1234")
        assertEquals("a new PIN starts with no count", PinCheck.Wrong(4), locks.verifyPin(a, "9999"))
    }

    /** 3.1, global constraints: as the tokens, out of the backup; the rule is the manifest's. */
    @Test fun theAppHasNoBackup() {
        val info = ApplicationProvider.getApplicationContext<android.content.Context>().applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
