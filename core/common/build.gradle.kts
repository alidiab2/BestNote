plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    testImplementation(libs.kotlinx.coroutines.test)
}
