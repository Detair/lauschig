package io.wolftown.lauschig.download

import androidx.work.NetworkType
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayDownloadStarterTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val enqueued = mutableListOf<Pair<String, NetworkType?>>()
    private val manager = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) { enqueued += itemId to network }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }
    private val prompts = DownloadPrompts()
    private val enabled = MutableStateFlow(true)
    private val mode = MutableStateFlow(MeteredDownload.ASK)
    private var metered = false
    private var row: DownloadEntity? = null

    private fun TestScope.starter() = PlayDownloadStarter(
        enabled = enabled,
        metered = mode,
        setMetered = { mode.value = it },
        isMetered = { metered },
        row = { _, _ -> row },
        describe = { _, id -> "Title of $id" to 123_000_000L },
        downloads = manager,
        prompts = prompts,
        scope = backgroundScope,
    )

    @Test fun settingOff_doesNothing() = runTest {
        enabled.value = false
        starter().onStreamStarted(s, "it")
        assertTrue(enqueued.isEmpty())
        assertNull(prompts.prompt.value)
    }

    @Test fun unmetered_enqueuesWithTheUsualRules() = runTest {
        starter().onStreamStarted(s, "it")
        assertEquals(listOf("it" to null), enqueued)
        assertNull(prompts.prompt.value)
    }

    @Test fun meteredAsk_promptsWithSize_withoutEnqueuing() = runTest {
        metered = true
        starter().onStreamStarted(s, "it")
        assertTrue(enqueued.isEmpty())
        assertEquals(DownloadPrompt.Metered(s, "it", "Title of it", 123_000_000L), prompts.prompt.value)
    }

    @Test fun meteredAlways_enqueuesOnAnyNetwork() = runTest {
        metered = true
        mode.value = MeteredDownload.ALWAYS
        starter().onStreamStarted(s, "it")
        assertEquals(listOf("it" to NetworkType.CONNECTED), enqueued)
    }

    @Test fun meteredWifi_enqueuesForUnmetered() = runTest {
        metered = true
        mode.value = MeteredDownload.WIFI
        starter().onStreamStarted(s, "it")
        assertEquals(listOf("it" to NetworkType.UNMETERED), enqueued)
    }

    @Test fun existingDownload_isLeftAlone() = runTest {
        metered = true
        for (state in listOf(DownloadState.COMPLETE, DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED)) {
            row = DownloadEntity(s, "it", state, 1)
            starter().onStreamStarted(s, "it")
        }
        assertTrue(enqueued.isEmpty())
        assertNull(prompts.prompt.value)
    }

    @Test fun pausedForTheStream_orFailed_startsAgain() = runTest {
        row = DownloadEntity(s, "it", DownloadState.PAUSED, 1, DownloadWorker.STREAM_PRIORITY)
        starter().onStreamStarted(s, "it")
        row = DownloadEntity(s, "it", DownloadState.FAILED, 1, "http_500")
        starter().onStreamStarted(s, "it")
        assertEquals(listOf("it" to null, "it" to null), enqueued)
    }

    @Test fun meteredAnswer_now_enqueuesOverMobile_withoutChangingTheSetting() = runTest {
        metered = true
        val st = starter()
        st.onStreamStarted(s, "it")
        st.answerMetered(prompts.prompt.value as DownloadPrompt.Metered, now = true, remember = false)
        assertEquals(listOf("it" to NetworkType.CONNECTED), enqueued)
        assertEquals(MeteredDownload.ASK, mode.value)
        assertNull(prompts.prompt.value)
    }

    @Test fun meteredAnswer_rememberNow_setsAlways() = runTest {
        metered = true
        val st = starter()
        st.onStreamStarted(s, "it")
        st.answerMetered(prompts.prompt.value as DownloadPrompt.Metered, now = true, remember = true)
        assertEquals(MeteredDownload.ALWAYS, mode.value)
    }

    @Test fun meteredAnswer_rememberWifi_waitsForWifiAndSetsWifi() = runTest {
        metered = true
        val st = starter()
        st.onStreamStarted(s, "it")
        st.answerMetered(prompts.prompt.value as DownloadPrompt.Metered, now = false, remember = true)
        assertEquals(listOf("it" to NetworkType.UNMETERED), enqueued)
        assertEquals(MeteredDownload.WIFI, mode.value)
        assertNull(prompts.prompt.value)
    }

    @Test fun dismiss_clearsWithoutDownloading() = runTest {
        metered = true
        val st = starter()
        st.onStreamStarted(s, "it")
        st.dismiss(prompts.prompt.value!!)
        assertNull(prompts.prompt.value)
        assertTrue(enqueued.isEmpty())
    }

    @Test fun contentionAnswer_download_resumesOnTheSameNetwork() = runTest {
        val p = DownloadPrompt.Contention(s, "it", "T", etaSec = 600, network = NetworkType.UNMETERED)
        prompts.show(p)
        starter().answerContention(p, download = true)
        assertEquals(listOf("it" to NetworkType.UNMETERED), enqueued)
        assertNull(prompts.prompt.value)
    }

    @Test fun contentionAnswer_keepStreaming_leavesDownloadPaused() = runTest {
        val p = DownloadPrompt.Contention(s, "it", "T", etaSec = null, network = NetworkType.CONNECTED)
        prompts.show(p)
        starter().answerContention(p, download = false)
        assertTrue(enqueued.isEmpty())
        assertNull(prompts.prompt.value)
    }

    @Test fun clearingAnOldPrompt_keepsANewerOne() = runTest {
        val old = DownloadPrompt.Metered(s, "a", "A", 1)
        val new = DownloadPrompt.Metered(s, "b", "B", 1)
        prompts.show(old)
        prompts.show(new)
        prompts.clear(old)
        assertEquals(new, prompts.prompt.value)
    }

    @Test fun pausedForTheStream_onMobileData_staysPaused() = runTest {
        metered = true
        mode.value = MeteredDownload.ALWAYS
        row = DownloadEntity(s, "it", DownloadState.PAUSED, 1, DownloadWorker.STREAM_PRIORITY)
        starter().onStreamStarted(s, "it")
        mode.value = MeteredDownload.ASK
        starter().onStreamStarted(s, "it")
        assertTrue(enqueued.isEmpty())
        assertNull(prompts.prompt.value)
    }

    @Test fun failedForLackOfStorage_isNotRetried() = runTest {
        row = DownloadEntity(s, "it", DownloadState.FAILED, 1, "storage_full")
        starter().onStreamStarted(s, "it")
        assertTrue(enqueued.isEmpty())
    }
}
