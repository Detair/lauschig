import java.time.Duration

// The Room database: entities, DAOs, the converters, the exported schemas (schemas/) and the pure rules the DAOs apply
// (queue order, bookmark outbox). Room's KSP runs here, apart from Compose and Hilt in :app; the Hilt bindings of the
// database and its DAOs stay in :app (db/DatabaseModule.kt).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.wolftown.lauschig.core.database"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    // No resources here: no R class, no resource tasks.
    androidResources {
        enable = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // As :app's targetSdk: Robolectric tests without an explicit @Config(sdk) run on the same SDK as before.
        targetSdk = 37
        unitTests.isIncludeAndroidResources = true
        // Same as :app: Robolectric (SDK 36+) reflects into JDK internals; a hanging test must fail fast.
        unitTests.all { it.timeout.set(Duration.ofMinutes(10)); it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    // BookmarkDao.mergeFromServer takes the server's BookmarkDto list.
    api(project(":abs-api"))

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}
