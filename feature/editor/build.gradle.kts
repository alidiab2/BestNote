plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.fonts)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    // LIB-11: lock gate, lock dialogs and FLAG_SECURE for locked notebooks.
    implementation(projects.core.security)
    implementation(projects.engine.canvas)
    implementation(projects.engine.pdf)
    implementation(projects.engine.recognition)
    implementation(projects.engine.export)
    // REC-05 "Convert with AI": the Smart Board provider the user picked reads the selection.
    implementation(projects.ai.visionProviders)
    // AI-08 Tidy: the neat handwriting is written by the Smart Board hand.
    implementation(projects.engine.handwriting)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
}
