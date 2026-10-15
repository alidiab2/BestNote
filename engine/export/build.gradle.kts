plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.compose)
    alias(libs.plugins.bestnote.android.hilt)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.storage)
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    // Paper (templates, colors, dark-paper ink mapping), the brush width model and template geometry.
    implementation(projects.engine.canvas)
    implementation(projects.engine.ink)
    implementation(projects.engine.layout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.material.icons.extended)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
