plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.datastore)
    implementation(libs.androidx.compose.material.icons.extended)
}
