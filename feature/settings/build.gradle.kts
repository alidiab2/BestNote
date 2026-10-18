plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.fonts)
    implementation(projects.core.datastore)
    implementation(projects.core.updater)
    implementation(projects.engine.export)
    // Smart Board key settings (Keystore-backed) and the key checker. Feature → ai is allowed.
    implementation(projects.ai.visionProviders)
    implementation(libs.androidx.compose.material.icons.extended)
    // AI-11 "Sign in with OpenRouter" opens the provider's page in a Custom Tab.
    implementation(libs.androidx.browser)
}
