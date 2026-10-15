plugins {
    alias(libs.plugins.bestnote.android.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.storage)
    // Backgrounds are handed to the page canvas through its PageBackgrounds hook.
    api(projects.engine.canvas)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
