plugins {
    alias(libs.plugins.bestnote.android.library)
}

// Smart Board provider choice: which BYOK provider is active, its Keystore-encrypted key, model
// and base URL, and the factory that builds the matching VisionProvider. Features depend on this
// module instead of on each provider.
dependencies {
    api(projects.ai.visionApi)
    api(projects.ai.visionGemini)
    api(projects.ai.visionClaude)
    api(projects.ai.visionOpenai)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
