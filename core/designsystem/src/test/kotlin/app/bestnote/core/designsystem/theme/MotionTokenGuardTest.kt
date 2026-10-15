package app.bestnote.core.designsystem.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DS-02 lint check: no hard-coded durations, easing curves or spring parameters anywhere outside
 * `Motion.kt`. Scans every Kotlin source in the repository, so it guards all modules from the
 * `:core:designsystem:test` task. (Can move into a custom detekt rule once QA-02 lands.)
 *
 * Escape hatch for a justified exception: put `// motion-ok: <reason>` on the line.
 */
class MotionTokenGuardTest {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""import\s+androidx\.compose\.animation\.core\.(tween|spring|keyframes|keyframesWithSpline|snap|repeatable|infiniteRepeatable|CubicBezierEasing|\*)\b""") to
            "build specs with Motion.* instead of importing raw animation spec builders",
        Regex("""\bdurationMillis\s*=\s*\d""") to "use Motion.Duration.*",
        Regex("""\bdelayMillis\s*=\s*\d""") to "use Motion.Duration.*",
        Regex("""\bCubicBezierEasing\s*\(""") to "use Motion.Easing.*",
        Regex("""\b(dampingRatio|stiffness)\s*=""") to "use Motion.Spring.*",
        Regex("""\bSpring\.(Stiffness|DampingRatio)\w+""") to "use Motion.Spring.*",
        Regex("""\.setDuration\s*\(\s*\d""") to "use Motion.Duration.*",
        Regex("""(?<![\w.])tween\s*(<[^>]*>)?\s*\(\s*\d""") to "use Motion.tween(Motion.Duration.*)",
    )

    @Test
    fun noHardCodedMotionOutsideMotionTokens() {
        val root = repoRoot()
        val violations = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "build-logic", "node_modules") }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.endsWith("/designsystem/theme/Motion.kt") || it.name == "MotionTokenGuardTest.kt" }
            .flatMap { file -> scan(file, root) }
            .toList()
        assertTrue(
            "Hard-coded motion outside Motion (DS-02):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun guardCatchesRawSpecs() {
        val sample = createTempFile(suffix = ".kt").apply {
            writeText("val a = tween<Float>(300)\nval b = spring<Float>(dampingRatio = 0.5f)\nfoo(durationMillis = 200)\n")
            deleteOnExit()
        }
        assertTrue(scan(sample, sample.parentFile).size >= 3)
    }

    private fun scan(file: File, root: File): List<String> =
        file.readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") || "motion-ok:" in line) return@mapIndexedNotNull null
            rules.firstOrNull { (re, _) -> re.containsMatchIn(line) }
                ?.let { (_, hint) -> "${file.relativeTo(root).path}:${i + 1}: $hint → $line" }
        }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private fun createTempFile(suffix: String): File = File.createTempFile("motion-guard", suffix)
}
