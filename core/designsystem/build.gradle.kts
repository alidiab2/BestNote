plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.compose)
}

dependencies {
    implementation(libs.androidx.compose.ui.text.google.fonts)
    debugImplementation(libs.androidx.activity.compose)
}
