package io.wolftown.lauschig.tv

/**
 * Robolectric qualifiers of a 1080p television: UI mode TELEVISION, no touchscreen, a D-pad. Every TV test runs with them,
 * so that `isTelevision()` holds and focus moves the way it does with a remote.
 */
const val TV_QUALIFIERS = "w960dp-h540dp-land-television-xhdpi-notouch-dpad"
