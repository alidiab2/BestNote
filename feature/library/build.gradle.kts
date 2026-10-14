plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.database)
    implementation(libs.androidx.compose.material.icons.extended)
}
