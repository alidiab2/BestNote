plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.engine.canvas)
    implementation(projects.engine.pdf)
    implementation(projects.engine.recognition)
    implementation(projects.engine.export)
    // REC-05 "Convert with AI": the Smart Board provider the user picked reads the selection.
    implementation(projects.ai.visionProviders)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
}
