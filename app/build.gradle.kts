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

// Web links of the Audiobookshelf UI (`/item/<id>`) open in Lauschig for these hosts (comma-separated Gradle property
// `lauschig.linkHosts`). Manifests cannot list hosts at run time; without assetlinks on the server there is no
// autoVerify either: the user allows them once under Android's "Open by default" (see links/LinkSettings).
val linkHosts: List<String> = (providers.gradleProperty("lauschig.linkHosts").orNull ?: "audiobookshelf.wolftown.io")
    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    .onEach { require(Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*").matches(it)) { "lauschig.linkHosts: not a host name: $it" } }

/** Writes the VIEW intent filter for [hosts] into a manifest overlay that is merged into MainActivity. */
abstract class LinkManifestTask : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputFile abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val list = hosts.get()
        val filter = if (list.isEmpty()) "" else buildString {
            // No autoVerify on purpose: the server has no assetlinks.json (the user allows the hosts instead).
            appendLine("            <intent-filter tools:ignore=\"AppLinkWarning\">")
            appendLine("                <action android:name=\"android.intent.action.VIEW\" />")
            appendLine("                <category android:name=\"android.intent.category.DEFAULT\" />")
            appendLine("                <category android:name=\"android.intent.category.BROWSABLE\" />")
            appendLine("                <data android:scheme=\"https\" />")
            appendLine("                <data android:scheme=\"http\" />")
            list.forEach { appendLine("                <data android:host=\"$it\" />") }
            // Android's globs do not backtrack: ".*" stops at the next "/", so every prefix depth needs its own pattern.
            // pathPattern (all versions): up to 2 prefix segments ("/audiobookshelf/item/<id>");
            // pathAdvancedPattern (Android 12+): 3 and 4 prefix segments.
            val routes = listOf("/item/.*", "/library/.*/series/.*")
            routes.forEach { route ->
                listOf("", "/.*", "/.*/.*").forEach { appendLine("                <data android:pathPattern=\"$it$route\" />") }
            }
            listOf("/item/.*", "/library/[^/]+/series/.*").forEach { route ->
                listOf("/[^/]+/[^/]+/[^/]+", "/[^/]+/[^/]+/[^/]+/[^/]+").forEach {
                    appendLine("                <data android:pathAdvancedPattern=\"$it$route\" tools:targetApi=\"s\" />")
                }
            }
            appendLine("            </intent-filter>")
        }
        manifest.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from the Gradle property lauschig.linkHosts (app/build.gradle.kts). -->
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            |    xmlns:tools="http://schemas.android.com/tools">
            |    <application>
            |        <activity android:name="io.wolftown.lauschig.MainActivity" android:exported="true" tools:node="merge">
            |$filter        </activity>
            |    </application>
            |</manifest>
            |""".trimMargin(),
        )
    }
}

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
        versionCode = 2
        versionName = "0.2.0"
        // Instrumented tests (app/src/androidTest): the media session as Android Auto and SystemUI see it.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LINK_HOSTS", "\"${linkHosts.joinToString(",")}\"")
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

androidComponents {
    onVariants { variant ->
        val task = tasks.register<LinkManifestTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}LinkManifest") {
            hosts.set(linkHosts)
            manifest.set(layout.buildDirectory.file("generated/linkManifest/${variant.name}/AndroidManifest.xml"))
        }
        variant.sources.manifests.addGeneratedManifestFile(task, LinkManifestTask::manifest)
    }
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
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
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
