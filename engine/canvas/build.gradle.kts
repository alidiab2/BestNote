plugins {
    alias(libs.plugins.bestnote.android.library)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(projects.core.model)
    api(projects.engine.ink)
    api(projects.engine.stylus)
    api(projects.engine.layout)
    implementation(projects.engine.recognition)
    implementation(libs.androidx.core.ktx)

    api(libs.androidx.ink.authoring)
    api(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    // InProgressStrokesView renders wet ink through androidx.graphics' front-buffered renderer.
    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)

    // CANVAS-06 redraw benchmark (emulator: scripts in TileRedrawBenchmark's doc).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
