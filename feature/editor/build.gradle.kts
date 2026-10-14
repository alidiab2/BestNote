plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.engine.canvas)
    implementation(libs.androidx.compose.material.icons.extended)
}
