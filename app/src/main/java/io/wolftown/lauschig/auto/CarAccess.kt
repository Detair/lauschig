package io.wolftown.lauschig.auto

/**
 * Who may do what with the media session. [Level.FULL] (browse, search, the car buttons, cover access): this app,
 * callers the system trusts (system apps, holders of `MEDIA_CONTENT_CONTROL`, see `ControllerInfo.isTrusted`), the car
 * hosts and the Assistant. Everyone else gets [Level.CONTROL]: the player's own commands (play, pause, seek), so a
 * watch or automation app can pause, but no library, no search and no covers: titles are personal data.
 */
object CarAccess {
    enum class Level { FULL, CONTROL }

    /** Exact package names; no prefixes (a look-alike package must not pass). */
    val FULL_ACCESS = setOf(
        "com.google.android.projection.gearhead", // Android Auto
        "com.android.car.media", // Android Automotive media center
        "com.android.car.carlauncher", // Android Automotive launcher (media card)
        "com.google.android.carassistant", // Assistant on Android Automotive
        "com.google.android.googlequicksearchbox", // Google Assistant / Gemini
        "com.android.systemui", // media controls and playback resumption
    )

    /**
     * System components that read the playing title's artwork from the platform media session without ever connecting
     * to us (SystemUI's media controls, Bluetooth AVRCP cover art), plus the car hosts: they get the cover grant when
     * the service starts.
     */
    val COVER_READERS = FULL_ACCESS + setOf("com.android.bluetooth", "com.google.android.bluetooth")

    fun levelFor(packageName: String, ownPackage: String, trusted: Boolean): Level =
        if (trusted || packageName == ownPackage || packageName in FULL_ACCESS) Level.FULL else Level.CONTROL

    /** Covers are granted to every other caller with full access (this app reads its own provider anyway). */
    fun grantsCovers(packageName: String, ownPackage: String, trusted: Boolean): Boolean =
        packageName != ownPackage && levelFor(packageName, ownPackage, trusted) == Level.FULL
}
