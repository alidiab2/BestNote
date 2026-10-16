plugins {
    alias(libs.plugins.bestnote.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// TPL-02: user templates (JSON in app storage, export/import) and the "Design with AI" protocol. Pure JVM.
dependencies {
    api(projects.core.model)
    api(projects.engine.layout)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
