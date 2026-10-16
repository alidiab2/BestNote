plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.database)
    implementation(projects.core.storage)
    // LIB-11: lock dialogs, Keystore unlock and the relock session.
    implementation(projects.core.security)
    implementation(projects.engine.pdf)
    implementation(projects.engine.recognition)
    implementation(libs.androidx.activity.compose)
    implementation(projects.engine.export)
    implementation(libs.androidx.compose.material.icons.extended)
}
