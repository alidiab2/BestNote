plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.core.common)
    testImplementation(libs.kotlinx.coroutines.test)
}
