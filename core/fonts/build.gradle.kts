plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.compose)
}

// TPL-02: the font registry (bundled + downloadable OFL + the user's own), validation and the font picker.
dependencies {
    implementation(projects.core.common)
    api(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
}
