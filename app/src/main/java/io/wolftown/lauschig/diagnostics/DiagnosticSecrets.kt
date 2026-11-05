package io.wolftown.lauschig.diagnostics

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.account.KeptAccountStore
import io.wolftown.lauschig.rmab.RmabConfig
import io.wolftown.lauschig.rmab.RmabRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * Tells the [LogMasker] the user's own values, so they are masked wherever they turn up in a log line, also where no
 * pattern would recognise them (a bare host name, a user name in a message): the server hosts and user names of the
 * signed-in and the kept accounts, the home Wi-Fi names, the ReadMeABook host and user. Kept current while the app runs.
 */
class DiagnosticSecrets(
    private val account: Flow<Account?>,
    private val kept: Flow<List<KeptAccount>>,
    private val rmab: Flow<RmabConfig?>,
    /** Hands the values to the masker and masks the entries kept so far once more (they predate the values). */
    private val apply: (Collection<String>) -> Unit = { LogMasker.setLiterals(it); DiagnosticLog.remask() },
) {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, rmab: RmabRepository) : this(accounts.account, kept.accounts, rmab.config)

    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                combine(
                    account.catch { emit(null) },
                    kept.onStart { emit(emptyList()) }.catch { emit(emptyList()) },
                    rmab.onStart { emit(null) }.catch { emit(null) },
                ) { a, k, r -> literals(a, k, r) }.collect { apply(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "own values for masking not available", e)
            }
        }
    }

    companion object {
        private const val TAG = "DiagnosticSecrets"

        fun literals(account: Account?, kept: List<KeptAccount>, rmab: RmabConfig?): Set<String> = buildSet {
            fun host(h: String) { add(h); parentDomain(h)?.let(::add) }
            account?.let { a ->
                listOfNotNull(a.addresses.local, a.addresses.public).forEach { host(it.host) }
                add(a.username)
                addAll(a.addresses.homeSsids)
            }
            kept.forEach { host(it.host.substringBefore(':')); add(it.username) }
            rmab?.let { r ->
                r.url.toHttpUrlOrNull()?.host?.let(::host)
                add(r.username)
            }
        }.filter { it.isNotBlank() }.toSet()

        /**
         * The registrable domain above a host (`example.io` for `abs.example.io`, `example.co.uk` for `abs.example.co.uk`):
         * certificates name it as `*.example.io`, other services of the same family use it. Null for an IP address or a
         * host that is that domain already.
         */
        fun parentDomain(host: String): String? {
            if (':' in host || host.all { it.isDigit() || it == '.' }) return null
            val labels = host.lowercase().split('.').filter { it.isNotEmpty() }
            val take = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL && labels.last().length == 2) 3 else 2
            return if (labels.size > take) labels.takeLast(take).joinToString(".") else null
        }

        private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")
    }
}
