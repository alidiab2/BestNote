package app.bestnote.engine.handwriting

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn shapes for the symbols the Hershey ASCII sets lack (Greek, operators, arrows).
 * Units: x-height = 1, baseline at y = 0, y down (so -1 is the x-height line). Each glyph is a
 * list of pen strokes in writing order plus its advance. A one-point stroke is a dot.
 */
internal object Symbols {
    class SGlyph(val strokes: List<Poly>, val advance: Float)

    private fun p(vararg v: Float): Poly = (v.indices step 2).map { P(v[it], v[it + 1]) }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, start: Float = -PI.toFloat() / 2, sweep: Float = 2.1f * PI.toFloat()): Poly =
        (0..36).map { i ->
            val a = start - sweep * i / 36
            P(cx + cos(a) * rx, cy + sin(a) * ry)
        }

    private val table: Map<Char, SGlyph> = buildMap {
        put('θ', SGlyph(listOf(oval(0.38f, -0.75f, 0.32f, 0.75f), p(0.1f, -0.75f, 0.68f, -0.76f)), 0.9f))
        put('π', SGlyph(listOf(p(-0.02f, -0.92f, 0.3f, -1.02f, 1.0f, -1.0f), p(0.3f, -1f, 0.24f, -0.4f, 0.12f, 0f), p(0.7f, -1f, 0.7f, -0.2f, 0.85f, 0f)), 1.1f))
        put('Δ', SGlyph(listOf(p(0.55f, -1.5f, 0f, 0f, 1.1f, 0f, 0.55f, -1.5f)), 1.25f))
        put('α', SGlyph(listOf(p(0.95f, -1f, 0.8f, -0.45f, 0.58f, -0.1f, 0.32f, 0f, 0.08f, -0.25f, 0.12f, -0.75f, 0.42f, -1f, 0.64f, -0.7f, 0.8f, -0.2f, 0.98f, 0f)), 1.1f))
        put('β', SGlyph(listOf(p(0.12f, 0.6f, 0.12f, -1.3f, 0.35f, -1.55f, 0.6f, -1.38f, 0.48f, -0.98f, 0.22f, -0.9f, 0.58f, -0.8f, 0.72f, -0.4f, 0.46f, -0.02f, 0.14f, -0.15f)), 0.9f))
        put('γ', SGlyph(listOf(p(0f, -0.95f, 0.18f, -1f, 0.42f, -0.3f, 0.45f, 0.6f, 0.38f, 0.65f, 0.4f, 0.1f, 0.8f, -1f)), 0.9f))
        put('λ', SGlyph(listOf(p(0.05f, -1.5f, 0.3f, -1.2f, 0.8f, 0f), p(0.47f, -0.75f, 0f, 0f)), 0.9f))
        put('μ', SGlyph(listOf(p(0.12f, 0.6f, 0.12f, -1f), p(0.12f, -0.3f, 0.35f, 0f, 0.6f, -0.15f, 0.72f, -1f), p(0.72f, -1f, 0.74f, -0.12f, 0.9f, 0f)), 1.0f))
        put('σ', SGlyph(listOf(p(1.05f, -1f, 0.45f, -1f, 0.12f, -0.6f, 0.3f, 0f, 0.65f, -0.1f, 0.75f, -0.55f, 0.45f, -1f)), 1.1f))
        put('ω', SGlyph(listOf(p(0.15f, -0.95f, 0.04f, -0.35f, 0.25f, 0f, 0.5f, -0.3f, 0.52f, -0.6f, 0.52f, -0.3f, 0.78f, 0f, 1.0f, -0.35f, 0.9f, -0.95f)), 1.15f))
        put('φ', SGlyph(listOf(oval(0.5f, -0.5f, 0.42f, 0.5f), p(0.55f, -1.5f, 0.45f, 0.55f)), 1.05f))
        put('ρ', SGlyph(listOf(p(0.12f, 0.6f, 0.15f, -0.5f, 0.4f, -1f, 0.75f, -0.75f, 0.7f, -0.2f, 0.4f, 0f, 0.15f, -0.2f)), 0.9f))
        put('τ', SGlyph(listOf(p(0f, -0.9f, 0.3f, -1f, 0.9f, -1f), p(0.46f, -1f, 0.42f, -0.15f, 0.6f, 0f)), 0.95f))
        put('ε', SGlyph(listOf(p(0.75f, -0.9f, 0.4f, -1f, 0.15f, -0.78f, 0.38f, -0.52f, 0.1f, -0.3f, 0.3f, 0f, 0.75f, -0.1f)), 0.85f))
        put('η', SGlyph(listOf(p(0.05f, -0.9f, 0.15f, -1f, 0.18f, 0f), p(0.18f, -0.7f, 0.5f, -1f, 0.75f, -0.75f, 0.72f, 0.6f)), 0.95f))
        put('Σ', SGlyph(listOf(p(1.0f, -1.45f, 0.05f, -1.5f, 0.6f, -0.75f, 0.05f, 0f, 1.0f, -0.02f)), 1.15f))
        put('Π', SGlyph(listOf(p(0f, -1.5f, 1.05f, -1.5f), p(0.22f, -1.5f, 0.22f, 0f), p(0.85f, -1.5f, 0.85f, 0f)), 1.2f))
        put('Ω', SGlyph(listOf(p(0.05f, 0f, 0.38f, 0f, 0.15f, -0.5f, 0.2f, -1.2f, 0.6f, -1.5f, 1.0f, -1.2f, 1.05f, -0.5f, 0.82f, 0f, 1.15f, 0f)), 1.25f))
        put('Φ', SGlyph(listOf(oval(0.55f, -0.75f, 0.5f, 0.45f), p(0.55f, -1.5f, 0.55f, 0f)), 1.2f))
        put('∫', SGlyph(listOf(p(0.78f, -1.62f, 0.6f, -1.8f, 0.42f, -1.4f, 0.36f, 0.3f, 0.2f, 0.72f, 0.02f, 0.55f)), 0.85f))
        put('∑', table0('Σ'))
        put('×', SGlyph(listOf(p(0.12f, -0.82f, 0.72f, -0.18f), p(0.72f, -0.82f, 0.12f, -0.18f)), 0.85f))
        put('·', SGlyph(listOf(p(0.3f, -0.5f)), 0.6f))
        put('•', SGlyph(listOf(oval(0.32f, -0.55f, 0.14f, 0.14f, sweep = 4.2f * PI.toFloat())), 0.75f))
        put('÷', SGlyph(listOf(p(0.05f, -0.5f, 0.85f, -0.5f), p(0.45f, -0.9f), p(0.45f, -0.1f)), 0.95f))
        put('±', SGlyph(listOf(p(0.45f, -1.15f, 0.45f, -0.35f), p(0.08f, -0.75f, 0.82f, -0.75f), p(0.08f, -0.02f, 0.82f, -0.02f)), 0.95f))
        put('≤', SGlyph(listOf(p(0.82f, -1.15f, 0.1f, -0.78f, 0.82f, -0.42f), p(0.1f, -0.1f, 0.82f, -0.1f)), 0.95f))
        put('≥', SGlyph(listOf(p(0.1f, -1.15f, 0.82f, -0.78f, 0.1f, -0.42f), p(0.1f, -0.1f, 0.82f, -0.1f)), 0.95f))
        put('≠', SGlyph(listOf(p(0.05f, -0.68f, 0.9f, -0.68f), p(0.05f, -0.32f, 0.9f, -0.32f), p(0.7f, -1f, 0.25f, 0f)), 1.0f))
        put('≈', SGlyph(listOf(p(0.05f, -0.62f, 0.25f, -0.78f, 0.5f, -0.62f, 0.7f, -0.5f, 0.9f, -0.68f), p(0.05f, -0.25f, 0.25f, -0.42f, 0.5f, -0.25f, 0.7f, -0.12f, 0.9f, -0.3f)), 1.0f))
        put('→', SGlyph(listOf(p(0.05f, -0.5f, 1.25f, -0.5f), p(0.98f, -0.75f, 1.26f, -0.5f, 0.98f, -0.25f)), 1.4f))
        put('←', SGlyph(listOf(p(1.3f, -0.5f, 0.1f, -0.5f), p(0.37f, -0.75f, 0.09f, -0.5f, 0.37f, -0.25f)), 1.4f))
        put('⇒', SGlyph(listOf(p(0.05f, -0.66f, 1.0f, -0.66f), p(0.05f, -0.34f, 1.0f, -0.34f), p(0.82f, -0.92f, 1.25f, -0.5f, 0.82f, -0.08f)), 1.4f))
        put('⇐', SGlyph(listOf(p(1.3f, -0.66f, 0.35f, -0.66f), p(1.3f, -0.34f, 0.35f, -0.34f), p(0.53f, -0.92f, 0.1f, -0.5f, 0.53f, -0.08f)), 1.4f))
        put('↓', SGlyph(listOf(p(0.4f, -1.3f, 0.4f, 0.1f), p(0.15f, -0.2f, 0.4f, 0.12f, 0.65f, -0.2f)), 0.85f))
        put('∞', SGlyph(listOf(p(0.65f, -0.5f, 0.45f, -0.8f, 0.15f, -0.75f, 0.08f, -0.45f, 0.2f, -0.22f, 0.45f, -0.25f, 0.65f, -0.5f, 0.85f, -0.75f, 1.12f, -0.75f, 1.22f, -0.5f, 1.12f, -0.22f, 0.85f, -0.22f, 0.65f, -0.5f)), 1.35f))
        put('∂', SGlyph(listOf(p(0.22f, -1.35f, 0.5f, -1.52f, 0.78f, -1.15f, 0.76f, -0.45f, 0.48f, 0f, 0.14f, -0.18f, 0.16f, -0.6f, 0.46f, -0.82f, 0.76f, -0.62f)), 0.95f))
        put('°', SGlyph(listOf(oval(0.25f, -1.35f, 0.17f, 0.17f, sweep = 2.15f * PI.toFloat())), 0.55f))
        put('⊥', SGlyph(listOf(p(0.5f, -1.3f, 0.5f, 0f), p(0f, 0f, 1f, 0f)), 1.1f))
        put('⟂', table0('⊥'))
        put('∥', SGlyph(listOf(p(0.25f, -1.4f, 0.25f, 0.1f), p(0.6f, -1.4f, 0.6f, 0.1f)), 0.85f))
        put('∠', SGlyph(listOf(p(1.0f, -1.2f, 0f, 0f, 1.05f, 0f)), 1.15f))
        put('∈', SGlyph(listOf(p(0.85f, -1f, 0.4f, -1f, 0.1f, -0.5f, 0.4f, 0f, 0.85f, 0f), p(0.12f, -0.5f, 0.75f, -0.5f)), 1.0f))
        put('−', SGlyph(listOf(p(0.08f, -0.5f, 0.85f, -0.5f)), 0.95f))
        put('–', table0('−'))
        put('—', SGlyph(listOf(p(0.15f, -0.5f, 1.9f, -0.52f)), 2.2f))
        put('…', SGlyph(listOf(p(0.25f, -0.05f), p(0.75f, -0.05f), p(1.25f, -0.05f)), 1.7f))
        put('√', SGlyph(listOf(p(0f, -0.55f, 0.18f, -0.62f, 0.42f, 0.05f, 0.85f, -1.5f, 1.3f, -1.5f)), 1.35f))
        put('✓', SGlyph(listOf(p(0f, -0.55f, 0.35f, 0f, 1.0f, -1.2f)), 1.1f))
        put('’', SGlyph(listOf(p(0.3f, -1.5f, 0.22f, -1.1f)), 0.5f))
        put('‘', table0('’'))
        put('“', SGlyph(listOf(p(0.2f, -1.5f, 0.14f, -1.1f), p(0.5f, -1.5f, 0.44f, -1.1f)), 0.7f))
        put('”', table0('“'))
    }

    // Lazily resolved aliases inside the builder above.
    private fun table0(c: Char): SGlyph = when (c) {
        'Σ' -> SGlyph(listOf(p(1.0f, -1.45f, 0.05f, -1.5f, 0.6f, -0.75f, 0.05f, 0f, 1.0f, -0.02f)), 1.15f)
        '⊥' -> SGlyph(listOf(p(0.5f, -1.3f, 0.5f, 0f), p(0f, 0f, 1f, 0f)), 1.1f)
        '−' -> SGlyph(listOf(p(0.08f, -0.5f, 0.85f, -0.5f)), 0.95f)
        '’' -> SGlyph(listOf(p(0.3f, -1.5f, 0.22f, -1.1f)), 0.5f)
        else -> SGlyph(listOf(p(0.2f, -1.5f, 0.14f, -1.1f), p(0.5f, -1.5f, 0.44f, -1.1f)), 0.7f)
    }

    operator fun get(c: Char): SGlyph? = table[c]

    fun has(c: Char): Boolean = c in table
}
