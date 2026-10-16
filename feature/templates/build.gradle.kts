plugins {
    alias(libs.plugins.bestnote.android.feature)
}

// TPL-02: the template list and editor (look, AI instructions, elements), live preview, Design with AI.
dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.fonts)
    implementation(projects.engine.templates)
    implementation(projects.engine.layout)
    implementation(projects.engine.handwriting)
    implementation(projects.engine.canvas)
    implementation(projects.ai.visionApi)
    implementation(projects.ai.visionProviders)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
