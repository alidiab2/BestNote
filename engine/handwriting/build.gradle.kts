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
