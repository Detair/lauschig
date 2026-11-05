package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.finishedQuestionsDataStore by preferencesDataStore(name = "finished_questions", corruptionHandler = FinishedQuestions.corruptionHandler())

/** The "Download löschen?" question after "Als gehört markieren" for [key] of [serverId]. */
data class FinishedQuestion(val serverId: String, val key: String)

/**
 * The open "Download löschen?" questions. A question is noted on disk before the title is marked finished, so the
 * automatic clean-up leaves the title alone until it is answered ([holds]) – also across a rotation or a killed process.
 * The dialog lives at the top of the app ([open]) and survives a rotation; a question that is still open when the
 * process starts again counts as "Behalten" ([settleLeftovers]). Fails closed: a title whose answer could not be stored
 * stays held.
 */
@Singleton
class FinishedQuestions(
    private val store: DataStore<Preferences>,
    /** True while [serverId]'s automatic clean-up is on: only then "Behalten" needs a keep entry. */
    private val autoDeleteOn: suspend (serverId: String) -> Boolean,
    /** Puts "serverId:key" on the keep list ([AppSettings.setCleanupIgnored]). */
    private val keepEntry: suspend (String) -> Unit,
    private val deleteDownload: suspend (serverId: String, key: String) -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, app: AppSettings, settings: DownloadSettings, manager: DownloadManager) : this(
        context.finishedQuestionsDataStore,
        autoDeleteOn = { sid -> settings.autoDeleteFinished(sid).first() != AutoDeleteMode.NEVER },
        keepEntry = { app.setCleanupIgnored(it, true) },
        deleteDownload = { sid, key -> manager.cancel(sid, key, deleteFiles = true) },
    )

    private val _open = MutableStateFlow<List<FinishedQuestion>>(emptyList())

    /** The questions asked in this process and not answered yet; the first one is shown. */
    val open: StateFlow<List<FinishedQuestion>> = _open.asStateFlow()

    private val titles = java.util.concurrent.ConcurrentHashMap<FinishedQuestion, String>()

    /** The title the question names (several questions in a row stay apart); null if it was not known. */
    fun titleOf(q: FinishedQuestion): String? = titles[q]

    /** Held in memory too: a question whose note could not be written is still never deleted in this process. */
    private val memory = MutableStateFlow<Set<FinishedQuestion>>(emptySet())

    private val noted: Flow<Set<FinishedQuestion>> = store.data.map { p -> p[PENDING].orEmpty().mapNotNull(::decode).toSet() }

    /**
     * Every title whose question is not settled: noted on disk (also by an earlier process) or held in memory. A store
     * that cannot be read fails this flow – the clean-up then deletes nothing (it cannot know what is held).
     */
    val holds: Flow<Set<FinishedQuestion>> = combine(noted, memory) { a, b -> a + b }

    /**
     * Notes and shows the question naming [title]; call before the title is marked finished. The hold must survive the
     * process: if the note cannot be written, the title goes on the keep list instead; if that fails too, this throws
     * and the title must not be marked finished (nothing may count it before somebody answered).
     */
    suspend fun ask(q: FinishedQuestion, title: String? = null) {
        memory.update { it + q }
        val noted = try {
            store.edit { it[PENDING] = it[PENDING].orEmpty() + encode(q) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "question not noted on disk; the title is kept instead", e)
            false
        }
        if (!noted) {
            try {
                keepEntry("${q.serverId}:${q.key}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                memory.update { it - q }
                throw java.io.IOException("the question could not be noted", e)
            }
        }
        if (!title.isNullOrBlank()) titles[q] = title
        _open.update { if (q in it) it else it + q }
    }

    /** "Löschen": the download goes now. If that fails the title stays held (and the question closes). */
    suspend fun answerDelete(q: FinishedQuestion) = withContext(NonCancellable) {
        _open.update { it - q }
        titles.remove(q)
        settle(q) { deleteDownload(q.serverId, q.key) }
    }

    /**
     * "Behalten" (also dismissing the question): with the automatic clean-up on, the title goes on the keep list. Only
     * once that is stored the hold ends; otherwise it stays held and is settled again at the next start.
     */
    suspend fun answerKeep(q: FinishedQuestion) = withContext(NonCancellable) {
        _open.update { it - q }
        titles.remove(q)
        settle(q) { keep(q) }
    }

    /** At the start of a process: a question nobody answered counts as "Behalten". */
    suspend fun settleLeftovers() {
        val left = try { noted.first() } catch (e: Exception) { emptySet() }
        for (q in left) if (q !in _open.value) withContext(NonCancellable) { settle(q) { keep(q) } }
    }

    private suspend fun keep(q: FinishedQuestion) {
        if (autoDeleteOn(q.serverId)) keepEntry("${q.serverId}:${q.key}")
    }

    private suspend fun settle(q: FinishedQuestion, action: suspend () -> Unit) {
        try {
            action()
            store.edit { it[PENDING] = it[PENDING].orEmpty() - encode(q) }
            memory.update { it - q }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the answer could not be stored; the title stays held", e)
        }
    }

    private fun encode(q: FinishedQuestion) = "${q.serverId}|${q.key}"
    private fun decode(e: String): FinishedQuestion? = e.split('|').takeIf { it.size == 2 }?.let { FinishedQuestion(it[0], it[1]) }

    companion object {
        private const val TAG = "FinishedQuestions"
        private val PENDING = stringSetPreferencesKey("pending")

        /**
         * A corrupt file starts empty. The open questions it held are lost, so every account takes a new baseline
         * ([FinishedSeenStore.requestNewBaselines]): what is finished now – those titles included – never goes.
         */
        fun corruptionHandler() = ReplaceFileCorruptionHandler { e ->
            Log.w(TAG, "open questions were corrupt; every account takes a new baseline", e)
            FinishedSeenStore.requestNewBaselines()
            emptyPreferences()
        }
    }
}

/** The question at the top of the app (AppRoot). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FinishedQuestionViewModel @Inject constructor(
    private val questions: FinishedQuestions,
    settings: DownloadSettings,
    accounts: AccountStore,
) : ViewModel() {
    val question: StateFlow<List<FinishedQuestion>> = questions.open

    fun titleOf(q: FinishedQuestion): String? = questions.titleOf(q)

    /** What "Behalten" means depends on the signed-in account's clean-up being on. */
    val autoDeleting: StateFlow<Boolean> = accounts.account.map { it?.serverId }.distinctUntilChanged()
        .flatMapLatest { sid -> settings.autoDeleteFinished(sid) }.map { it != AutoDeleteMode.NEVER }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun delete(q: FinishedQuestion) { viewModelScope.launch { questions.answerDelete(q) } }
    fun keep(q: FinishedQuestion) { viewModelScope.launch { questions.answerKeep(q) } }
}
