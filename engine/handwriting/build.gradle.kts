plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

// AI-05: turns Smart Board blocks into real, timed handwriting strokes on ruled paper. Pure JVM.
// Glyph data in src/main/resources/handwriting: Hershey single-stroke fonts (public domain) and
// skeletonised Aref Ruqaa forms (SIL OFL 1.1), exported by tools/handwriting/export_glyphs.py.
dependencies {
    api(projects.core.model)
    api(projects.engine.layout)
    // MATH-02: "Typeset" equations are measured with the TeX typesetter and become equation elements.
    implementation(projects.engine.mathtype)
    implementation(libs.kotlinx.serialization.json)
}

// LAYOUT-QA: deep layout fuzzing with -Playoutfuzz.iterations=N (and -Playoutfuzz.seed, -Playoutfuzz.png=true, -Playoutfuzz.only=<case>).
tasks.withType<Test>().configureEach {
    listOf("layoutfuzz.iterations", "layoutfuzz.seed", "layoutfuzz.png", "layoutfuzz.only").forEach { key ->
        project.findProperty(key)?.let { systemProperty(key, it.toString()) }
    }
    if (listOf("layoutfuzz.iterations", "layoutfuzz.only", "layoutfuzz.png").any(project::hasProperty)) outputs.upToDateWhen { false }
}
