plugins {
    alias(libs.plugins.bestnote.android.feature)
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.datastore)
    // The scratch canvas is the real ink pipeline (PageCanvasView + PalmRejection), not a mock.
    implementation(projects.engine.canvas)
    implementation(libs.androidx.compose.material.icons.extended)
}
