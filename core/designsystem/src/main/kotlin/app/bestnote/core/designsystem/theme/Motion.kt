package app.bestnote.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween as composeTween
import androidx.compose.animation.core.Easing as ComposeEasing
import androidx.compose.animation.core.Spring as ComposeSpring

/**
 * Motion tokens. Prefer springs for anything the user directly manipulates (they are
 * interruptible and velocity-preserving); use durations + easing for fades and reveals.
 */
object Motion {
    object Duration {
        const val Short: Int = 120
        const val Medium: Int = 240
        const val Long: Int = 400
    }

    object Easing {
        /** Material 3 "emphasized" curve. */
        val Emphasized: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate: ComposeEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    object Spring {
        /** Toolbar toggles, selection handles: fast, no overshoot. */
        fun <T> snappy(): FiniteAnimationSpec<T> =
            spring(dampingRatio = ComposeSpring.DampingRatioNoBouncy, stiffness = ComposeSpring.StiffnessMedium)

        /** Sheets, cards, page transitions: soft with a hint of bounce. */
        fun <T> gentle(): FiniteAnimationSpec<T> =
            spring(dampingRatio = 0.85f, stiffness = ComposeSpring.StiffnessLow)

        /** Playful confirmation (e.g. a stroke snapping into a shape). */
        fun <T> bouncy(): FiniteAnimationSpec<T> =
            spring(dampingRatio = ComposeSpring.DampingRatioMediumBouncy, stiffness = ComposeSpring.StiffnessMedium)

        val Snappy: FiniteAnimationSpec<Float> = snappy()
        val Gentle: FiniteAnimationSpec<Float> = gentle()
        val Bouncy: FiniteAnimationSpec<Float> = bouncy()
    }

    /** A tween built from tokens, e.g. `Motion.tween(Motion.Duration.Short)`. */
    fun <T> tween(
        durationMillis: Int = Duration.Medium,
        easing: ComposeEasing = Easing.Standard,
    ): FiniteAnimationSpec<T> = composeTween(durationMillis = durationMillis, easing = easing)
}
