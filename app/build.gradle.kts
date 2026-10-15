plugins {
    alias(libs.plugins.bestnote.android.application)
    alias(libs.plugins.bestnote.android.compose)
    alias(libs.plugins.bestnote.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.bestnote"

    defaultConfig {
        applicationId = "app.bestnote"
        versionCode = 1
        versionName = "0.1.0"
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

    implementation(projects.feature.library)
    implementation(projects.feature.editor)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Debug-only handwriting replay harness (app/src/debug, QA-INK): drives PageCanvasView directly.
    debugImplementation(projects.engine.canvas)
    testImplementation(libs.junit4)
}
