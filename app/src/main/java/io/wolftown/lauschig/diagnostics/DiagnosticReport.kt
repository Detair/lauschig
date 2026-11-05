package io.wolftown.lauschig.diagnostics

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.download.VolumeDownloadRoots
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Facts about the app and device for the head of the shared log. An allowlist: nothing else is said about the device
 * or the account – no server, no user, no network name.
 */
data class DiagnosticFacts(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val androidRelease: String,
    val sdk: Int,
    val device: String,
    /** "online (home network)", "online (internet)" or "offline". */
    val connection: String,
    /** "internal storage", "SD card" or "internal storage (chosen SD card missing)". */
    val storage: String,
    val createdAt: Long,
)

/**
 * Builds the diagnostic log that "Diagnose-Log teilen" sends: [DiagnosticFacts] and the [DiagnosticLog] entries, written
 * to [FILE_NAME] in `cache/diagnostics/` – the only directory the [FileProvider] exposes – and shared through the system
 * share sheet ([shareIntent]).
 */
class DiagnosticReport(
    private val cacheDir: File,
    private val facts: suspend () -> DiagnosticFacts,
    private val entries: () -> List<String> = DiagnosticLog::entries,
    /** Before a new copy is written: the old one and its grant go ([discardShared]). */
    private val discard: () -> Unit = {},
) {
    @Inject constructor(@ApplicationContext context: Context, api: ApiProvider, roots: VolumeDownloadRoots) : this(
        context.cacheDir,
        facts = {
            val storage = withContext(Dispatchers.IO) {
                when {
                    roots.chosenMissing() -> "internal storage (chosen SD card missing)"
                    roots.target() != null -> "SD card"
                    else -> "internal storage"
                }
            }
            DiagnosticFacts(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                "${Build.MANUFACTURER} ${Build.MODEL}", connectionFact(api.connectionState.value), storage, System.currentTimeMillis(),
            )
        },
        discard = { discardShared(context) },
    )

    /** The whole text: facts, then every entry (oldest first), masked once more with every own value known now. */
    suspend fun text(): String = render(facts(), entries().map(LogMasker::maskLiterals))

    /** Writes [text] to the shared file (replacing the previous one) and returns it. */
    suspend fun write(): File {
        val text = text()
        return withContext(Dispatchers.IO) {
            discard()
            val dir = File(cacheDir, DIR).apply { mkdirs() }
            File(dir, FILE_NAME).apply { writeText(text) }
        }
    }

    companion object {
        const val DIR = "diagnostics"
        const val FILE_NAME = "lauschig-diagnose.txt"

        fun connectionFact(state: ConnectionState): String = when (state) {
            is ConnectionState.Online -> if (state.via == ConnectionState.Via.LOCAL) "online (home network)" else "online (internet)"
            ConnectionState.Offline -> "offline"
        }

        fun render(f: DiagnosticFacts, entries: List<String>): String = buildString {
            append("Lauschig diagnostic log\n")
            append("Created: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(f.createdAt))).append('\n')
            append("App: ").append(f.appVersion).append(" (").append(f.versionCode).append(") ").append(f.buildType).append('\n')
            append("Android: ").append(f.androidRelease).append(" (SDK ").append(f.sdk).append(")\n")
            append("Device: ").append(f.device).append('\n')
            append("Connection: ").append(f.connection).append('\n')
            append("Downloads go to: ").append(f.storage).append('\n')
            append("Entries: ").append(entries.size).append(" (oldest first; passwords, tokens, addresses and names are masked)\n")
            append("----\n")
            entries.forEach { append(it).append('\n') }
        }

        /** The share sheet for [file]: a `text/plain` attachment readable only by the app the user picks. */
        fun shareIntent(context: Context, file: File, subject: String, chooserTitle: String): Intent {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Lauschig takes shared text itself (Audiobookshelf links); its own log is not for it.
            return Intent.createChooser(send, chooserTitle)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        }

        fun authority(context: Context) = "${context.packageName}.diagnostics"

        /**
         * The copy shared last time goes and the app that got it loses its read grant: at the next start, at sign-out and
         * before a new share writes it again. Blocking.
         */
        fun discardShared(context: Context) {
            val file = File(File(context.cacheDir, DIR), FILE_NAME)
            try {
                context.revokeUriPermission(FileProvider.getUriForFile(context, authority(context), file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // No grant or no provider (tests): nothing to revoke.
            }
            File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        }
    }
}
