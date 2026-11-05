package io.wolftown.lauschig.cleanup

import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.settings.AppSettings
import javax.inject.Inject

/**
 * What the automatic clean-up keeps of one account besides its seen times ([FinishedSeenStore]): its setting, its open
 * "Download löschen?" questions and its keep entries.
 */
class AutoDeleteAccountState @Inject constructor(
    private val settings: DownloadSettings,
    private val questions: FinishedQuestions,
    private val app: AppSettings,
) {
    /**
     * [serverId]'s data was purged: signed in again, it starts like a new account – "Nie", nothing asked, nothing kept.
     * The setting goes first: if a later step fails, the clean-up is already off.
     */
    suspend fun forget(serverId: String) {
        settings.clearAutoDelete(serverId)
        questions.forget(serverId)
        app.forgetCleanupIgnored(serverId)
    }
}
