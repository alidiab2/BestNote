plugins {
    alias(libs.plugins.bestnote.android.feature)
}

// AI-04/AI-07: Smart Board capture → expert-student reading → handwritten review → insert.
dependencies {
    implementation(projects.core.designsystem)
    // AI-13: the font picker and installed fonts for "change the font".
    implementation(projects.core.fonts)
    implementation(projects.ai.visionApi)
    implementation(projects.ai.visionProviders)
    implementation(projects.ai.boardScan)
    // TPL-01: the notebook's Smart Board record (glossary, page notes for follow-up photos).
    implementation(projects.core.storage)
    // Font output: TextElements drawn by the page renderer (preview uses the same painter).
    implementation(projects.engine.canvas)
    implementation(projects.engine.layout)
    implementation(projects.engine.templates)
    implementation(projects.engine.handwriting)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
