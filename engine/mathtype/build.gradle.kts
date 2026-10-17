plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

// MATH-01: typesets LaTeX into vector glyph outlines and rules. Pure JVM, no dependencies.
// Glyph data in src/main/resources/math: outlines and OpenType MATH tables of STIX Two Math
// (SIL OFL 1.1), exported by tools/math/export_math_font.py.
