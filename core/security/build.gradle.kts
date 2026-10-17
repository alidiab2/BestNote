plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.compose)
    alias(libs.plugins.bestnote.android.hilt)
}

// LIB-11 locked notebooks: Android Keystore keys, biometric / device-credential unlock, the app PIN,
// the relock session and the shared lock dialogs. The crypto itself is pure JVM in :core:storage.
dependencies {
    api(projects.core.storage)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    api(libs.androidx.biometric)
    api(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
