plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.androidx.datastore)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
