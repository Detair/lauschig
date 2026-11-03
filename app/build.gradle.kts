import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.baselineprofile)
}

// Release signing comes from the environment (or Gradle properties); never from the repo.
// Without all four values, bundleRelease still succeeds but produces an UNSIGNED bundle (with a warning).
fun signingValue(name: String): String? =
    (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("LAUSCHIG_KEYSTORE")
val hasReleaseSigning = keystorePath != null &&
    signingValue("LAUSCHIG_KEYSTORE_PASSWORD") != null &&
    signingValue("LAUSCHIG_KEY_ALIAS") != null &&
    signingValue("LAUSCHIG_KEY_PASSWORD") != null

android {
    namespace = "io.wolftown.lauschig"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.wolftown.lauschig"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Instrumented tests (app/src/androidTest): the media session as Android Auto and SystemUI see it.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = signingValue("LAUSCHIG_KEYSTORE_PASSWORD")
                keyAlias = signingValue("LAUSCHIG_KEY_ALIAS")
                keyPassword = signingValue("LAUSCHIG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric (SDK 36+) reflects into JDK internals (FileDescriptor).
        // A hanging test must fail fast instead of spinning forever.
        unitTests.all { it.timeout.set(Duration.ofMinutes(10)); it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

kotlin {
    jvmToolchain(21)
}

// Warn whenever a release artifact is built without complete signing config (any of the four values missing).
if (!hasReleaseSigning) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("bundle") || it.name.startsWith("assemble") || it.name.startsWith("package")) }) {
            logger.warn(
                "WARNING: release signing incomplete (LAUSCHIG_KEYSTORE, LAUSCHIG_KEYSTORE_PASSWORD, LAUSCHIG_KEY_ALIAS, " +
                    "LAUSCHIG_KEY_PASSWORD must all be set): the release build will be UNSIGNED.",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":abs-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.glance.appwidget)
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.media)
    androidTestImplementation(libs.junit)
}
