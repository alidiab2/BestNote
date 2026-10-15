plugins {
    alias(libs.plugins.bestnote.android.feature)
}

// AI-04/AI-07: Smart Board capture → expert-student reading → handwritten review → insert.
dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.ai.visionApi)
    implementation(projects.ai.visionGemini)
    implementation(projects.engine.layout)
    implementation(projects.engine.handwriting)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
