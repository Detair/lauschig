package io.wolftown.lauschig.download

import androidx.work.NetworkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A question about a download that waits for the user; answered through [PlayDownloadStarter]. */
sealed interface DownloadPrompt {
    val serverId: String
    val itemId: String
    val title: String

    /** Play started a stream on mobile data: download now over it, or wait for Wi-Fi? [bytes] is the whole title. */
    data class Metered(override val serverId: String, override val itemId: String, override val title: String, val bytes: Long) : DownloadPrompt

    /**
     * The stream stalled although the download was throttled; the download is paused. [etaSec]: how long the rest of
     * the download would take at the current connection rate (null if unknown). [network]: its constraint, for resuming.
     */
    data class Contention(
        override val serverId: String,
        override val itemId: String,
        override val title: String,
        val etaSec: Long?,
        val network: NetworkType?,
    ) : DownloadPrompt
}

/** The open download question; stays until answered, so it is still there when the app comes back to the foreground. */
@Singleton
class DownloadPrompts @Inject constructor() {
    private val _prompt = MutableStateFlow<DownloadPrompt?>(null)
    val prompt: StateFlow<DownloadPrompt?> = _prompt.asStateFlow()

    /** Replaces an open question (the newest situation wins). */
    fun show(prompt: DownloadPrompt) { _prompt.value = prompt }

    /** Closes [prompt] if it is still the open one. */
    fun clear(prompt: DownloadPrompt) { _prompt.update { if (it == prompt) null else it } }

    /** Sign-out: an open question about a download of [serverId] goes. */
    fun clearFor(serverId: String) { _prompt.update { if (it?.serverId == serverId) null else it } }
}
