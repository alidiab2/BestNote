plugins {
    alias(libs.plugins.bestnote.android.application)
    alias(libs.plugins.bestnote.android.compose)
    alias(libs.plugins.bestnote.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Short commit hash for the debug log; "unknown" outside a git checkout.
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short=10", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.orElse("unknown")

android {
    namespace = "app.bestnote"

    defaultConfig {
        applicationId = "app.bestnote"
        versionCode = 1
        versionName = "0.1.0"
        // Settings → Debug log: which commit this build came from.
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Per-app language (Settings → Appearance): only the languages we ship.
        localeFilters += listOf("en", "ar")
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            // -PdebugIdSuffix=.qaink installs side by side with other agents' builds on the shared emulator.
            applicationIdSuffix = ".debug" + (findProperty("debugIdSuffix") as String? ?: "")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.ai.visionGemini)
    implementation(projects.ai.visionProviders)

    implementation(projects.engine.export)
    implementation(projects.engine.recognitionMlkit)
    implementation(projects.feature.library)
    implementation(projects.feature.editor)
    implementation(projects.feature.settings)
    implementation(projects.feature.smartboard)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Debug-only handwriting replay harness (app/src/debug, QA-INK): drives PageCanvasView directly.
    debugImplementation(projects.engine.canvas)
    testImplementation(libs.junit4)
}
