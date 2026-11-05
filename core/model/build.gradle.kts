// Pure Kotlin types shared by every layer (no Android, no Compose): keys and ids that the database, playback and the
// UI all speak. Nothing here may depend on Room entities or on :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Android lint for a plain Kotlin module, so the root `lint` task (CI) checks it too.
    alias(libs.plugins.android.lint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
