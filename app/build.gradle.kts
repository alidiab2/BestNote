plugins {
    alias(libs.plugins.bestnote.android.application)
    alias(libs.plugins.bestnote.android.compose)
    alias(libs.plugins.bestnote.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Git facts for the version. All fall back safely when git is missing (source tarball) or the
// clone is shallow (CI default), so the build never fails because of them.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val gitCommit = git("rev-parse", "--short=10", "HEAD") ?: "unknown"
val shallow = git("rev-parse", "--is-shallow-repository") == "true"

// versionCode = number of commits on HEAD, so it grows with every merge and releases compare by it.
// Override with -PversionCodeOverride=N or BESTNOTE_VERSION_CODE; a shallow clone or no git gives 1.
val commitCount: Int = (findProperty("versionCodeOverride") as String?)?.toIntOrNull()
    ?: System.getenv("BESTNOTE_VERSION_CODE")?.toIntOrNull()
    ?: (if (shallow) null else git("rev-list", "--count", "HEAD")?.toIntOrNull())
    ?: 1

android {
    namespace = "app.bestnote"

    defaultConfig {
        applicationId = "app.bestnote"
        versionCode = commitCount
        versionName = "0.1.$commitCount-${gitCommit.take(7)}"
        // Settings → Debug log: which commit this build came from.
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit}\"")
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
    implementation(projects.core.ui)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.updater)
    implementation(projects.core.security)
    implementation(projects.ai.visionGemini)
    implementation(projects.ai.visionProviders)

    implementation(projects.engine.export)
    // LIB-11b: the start-up sweep removes plain PDF copies older builds cached for locked notebooks.
    implementation(projects.engine.pdf)
    implementation(projects.engine.recognitionMlkit)
    implementation(projects.feature.library)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.editor)
    implementation(projects.feature.settings)
    implementation(projects.feature.smartboard)
    implementation(projects.feature.templates)
    implementation(projects.core.fonts)
    implementation(projects.engine.templates)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Smart Board handwriting fonts are registered with the page renderer; the debug replay harness
    // (app/src/debug, QA-INK) drives PageCanvasView directly.
    implementation(projects.engine.canvas)
    testImplementation(libs.junit4)
}
