plugins {
    alias(libs.plugins.bestnote.android.library)
}

// OpenAI and OpenAI-compatible (OpenRouter, Groq, DeepSeek, Ollama) provider for Smart Board (ADR-0007).
// Provider modules are the only ones that declare INTERNET. Bring-your-own-key, no BestNote server.
dependencies {
    api(projects.ai.visionApi)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
