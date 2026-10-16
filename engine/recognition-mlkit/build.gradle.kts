plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.hilt)
}

dependencies {
    api(projects.engine.recognition)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.storage)
    implementation(projects.core.database)
    // Page pictures for search results.
    implementation(projects.engine.export)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.digital.ink)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
