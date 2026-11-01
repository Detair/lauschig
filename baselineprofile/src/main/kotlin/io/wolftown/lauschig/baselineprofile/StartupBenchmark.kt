package io.wolftown.lauschig.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the first frame of the library. Target: < 800 ms on a mid-range device.
 * The value is recorded in the benchmark output; the test never fails on it.
 * Run it with the device in airplane mode (and a signed-in, synced account) to measure the offline path.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartOffline() = measure(CompilationMode.None())

    @Test
    fun coldStartOfflineWithBaselineProfile() = measure(CompilationMode.Partial())

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startupJourney()
    }
}
