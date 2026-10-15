plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.datastore)
    // Smart Board key settings (Keystore-backed) and the key checker. Feature → ai is allowed.
    implementation(projects.ai.visionGemini)
    implementation(libs.androidx.compose.material.icons.extended)
}
