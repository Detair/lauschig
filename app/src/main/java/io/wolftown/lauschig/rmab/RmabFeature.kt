package io.wolftown.lauschig.rmab

import io.wolftown.lauschig.BuildConfig

/**
 * ReadMeABook is switched off for the launch (2026-11-15); the code stays. The Gradle property `lauschig.rmab=true`
 * builds it in (app/build.gradle.kts).
 *
 * Off means: no settings section, no request button and no status symbol on a missing volume ([RmabSettingsSection],
 * [rmabBrowse]), not a single call to a ReadMeABook server, and the connections an earlier build stored are removed at
 * the next start ([RmabRepository]). The missing volumes themselves stay: they come from the Audible catalog.
 */
object RmabFeature {
    const val ENABLED: Boolean = BuildConfig.RMAB
}
