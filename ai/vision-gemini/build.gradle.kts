plugins {
    alias(libs.plugins.bestnote.android.library)
}

// Gemini provider for Smart Board (ADR-0007). The only kind of module that declares INTERNET;
// the `foss` flavor leaves it out. Bring-your-own-key: no BestNote server, no account.
dependencies {
    api(projects.ai.visionApi)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
