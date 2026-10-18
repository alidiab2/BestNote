plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

// AI-04: board/document edge detection, perspective correction and auto-enhance. Pure JVM, no
// OpenCV and no Play Services, so it works the same in the foss build. Pixels are ARGB IntArrays.

// AI-12: figures redrawn from the board photo into the note's vector drawing types.
dependencies {
    api(projects.core.model)
    api(projects.engine.layout)
}
