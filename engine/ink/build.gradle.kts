plugins {
    alias(libs.plugins.bestnote.android.library)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.strokes)
    api(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.geometry)
}
