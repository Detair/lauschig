package io.wolftown.lauschig.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore by preferencesDataStore(name = "playback_settings")

/** Player comfort preferences (device-local). Per-title speed and boost live in the `title_audio` table. */
@Singleton
class PlaybackSettings(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.playbackDataStore)

    /** Seconds to jump back when resuming after a pause (scaled by the pause length); 0 = off. Default 5. */
    val rewindOnResumeSec: Flow<Int> = store.data.map { p -> p[REWIND]?.takeIf { it in REWIND_CHOICES } ?: DEFAULT_REWIND }

    /** Speed of titles without their own ("Standardtempo"). Default 1.0. */
    val defaultSpeed: Flow<Float> = store.data.map { it[DEFAULT_SPEED]?.takeIf { s -> s in 0.5f..3f } ?: 1f }

    /** Skip silent passages; global. Default off. */
    val skipSilence: Flow<Boolean> = store.data.map { it[SKIP_SILENCE] ?: false }

    /** Boost (dB) of titles without their own. Default 0 (off). */
    val defaultBoostDb: Flow<Int> = store.data.map { p -> p[DEFAULT_BOOST]?.takeIf { it in BOOST_CHOICES } ?: 0 }

    /** Resume the last title when a car or headphones connect. Default off. */
    val autoResumeOnConnect: Flow<Boolean> = store.data.map { it[AUTO_RESUME] ?: false }

    /** Start the last title when the app opens. Default off. */
    val continueOnStart: Flow<Boolean> = store.data.map { it[CONTINUE_ON_START] ?: false }

    /** Seconds the back button jumps (app, notification, car, headset); one of [SEEK_BACK_CHOICES]. Default 15. */
    val seekBackSec: Flow<Int> = store.data.map { p -> p[SEEK_BACK]?.takeIf { it in SEEK_BACK_CHOICES } ?: DEFAULT_SEEK_BACK }.distinctUntilChanged()

    /** Seconds the forward button jumps; one of [SEEK_FORWARD_CHOICES]. Default 30. */
    val seekForwardSec: Flow<Int> = store.data.map { p -> p[SEEK_FORWARD]?.takeIf { it in SEEK_FORWARD_CHOICES } ?: DEFAULT_SEEK_FORWARD }.distinctUntilChanged()

    suspend fun setSeekBackSec(sec: Int) {
        if (sec in SEEK_BACK_CHOICES) store.edit { it[SEEK_BACK] = sec }
    }

    suspend fun setSeekForwardSec(sec: Int) {
        if (sec in SEEK_FORWARD_CHOICES) store.edit { it[SEEK_FORWARD] = sec }
    }

    suspend fun setRewindOnResumeSec(sec: Int) {
        if (sec in REWIND_CHOICES) store.edit { it[REWIND] = sec }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        store.edit { it[DEFAULT_SPEED] = speed.coerceIn(0.5f, 3f) }
    }

    suspend fun setSkipSilence(on: Boolean) {
        store.edit { it[SKIP_SILENCE] = on }
    }

    suspend fun setDefaultBoostDb(db: Int) {
        if (db in BOOST_CHOICES) store.edit { it[DEFAULT_BOOST] = db }
    }

    suspend fun setAutoResumeOnConnect(on: Boolean) {
        store.edit { it[AUTO_RESUME] = on }
    }

    suspend fun setContinueOnStart(on: Boolean) {
        store.edit { it[CONTINUE_ON_START] = on }
    }

    companion object {
        val REWIND_CHOICES = listOf(0, 3, 5, 10)
        const val DEFAULT_REWIND = 5
        val BOOST_CHOICES = listOf(0, 3, 6, 9)
        val SPEED_CHOICES = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
        val SEEK_BACK_CHOICES = listOf(5, 10, 15, 30)
        val SEEK_FORWARD_CHOICES = listOf(15, 30, 45, 60)
        const val DEFAULT_SEEK_BACK = 15
        const val DEFAULT_SEEK_FORWARD = 30
        private val REWIND = intPreferencesKey("rewind_on_resume_sec")
        private val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        private val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        private val DEFAULT_BOOST = intPreferencesKey("default_boost_db")
        private val AUTO_RESUME = booleanPreferencesKey("auto_resume_on_connect")
        private val CONTINUE_ON_START = booleanPreferencesKey("continue_on_start")
        private val SEEK_BACK = intPreferencesKey("seek_back_sec")
        private val SEEK_FORWARD = intPreferencesKey("seek_forward_sec")
    }
}
