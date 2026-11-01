plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.wolftown.lauschig.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Run against the connected device (Fabian's phone): ./gradlew :app:generateBaselineProfile
    useConnectedDevices = true
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId ?: "io.wolftown.lauschig" },
        )
    }
}
