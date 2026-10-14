plugins {
    alias(libs.plugins.bestnote.android.library)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.strokes)
}
