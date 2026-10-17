plugins {
    alias(libs.plugins.bestnote.jvm.library)
}

dependencies {
    // MATH-01: EquationReaderTest drives the (suspend) recognizer interface with a recorded answer.
    testImplementation(libs.kotlinx.coroutines.core)
}
