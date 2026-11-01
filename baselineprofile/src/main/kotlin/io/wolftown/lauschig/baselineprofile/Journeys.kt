package io.wolftown.lauschig.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val TARGET_PACKAGE = "io.wolftown.lauschig"

private const val UI_TIMEOUT_MS = 10_000L

/** Cold start up to the first drawn frame of the app (the library is read from Room, no network). */
fun MacrobenchmarkScope.startupJourney() {
    pressHome()
    startActivityAndWait()
}

/** Starts the app, opens the Library tab and scrolls the cover grid down and back up. */
fun MacrobenchmarkScope.libraryScrollJourney() {
    startActivityAndWait()
    // Tab labels are localized (EN "Library" / DE "Bibliothek").
    val tab = device.wait(Until.findObject(By.text("Library")), UI_TIMEOUT_MS)
        ?: device.wait(Until.findObject(By.text("Bibliothek")), UI_TIMEOUT_MS)
    tab?.click()
    device.waitForIdle()
    val grid = device.wait(Until.findObject(By.scrollable(true)), UI_TIMEOUT_MS) ?: return
    grid.setGestureMargin(device.displayWidth / 5)
    repeat(3) { grid.fling(Direction.DOWN) }
    device.waitForIdle()
    repeat(3) { grid.fling(Direction.UP) }
    device.waitForIdle()
}
