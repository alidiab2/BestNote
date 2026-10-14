plugins {
    alias(libs.plugins.bestnote.android.library)
    alias(libs.plugins.bestnote.android.hilt)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}
