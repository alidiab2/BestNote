package app.bestnote.core.designsystem.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Easing as ComposeEasing
import androidx.compose.animation.core.tween as composeTween

/**
 * Motion tokens. The ONLY place in the codebase allowed to contain raw durations, easing curves
 * or spring parameters (enforced by `MotionTokenGuardTest`).
 *
 * Springs for anything the user drives (interruptible, velocity-preserving); tweens built from
 * [Duration] + [Easing] for fades and color changes. Nothing animates under an active pen, and ink
 * never has an appear animation.
 *
 * Reduced motion: read [BestNoteTheme.reducedMotion] (system "Remove animations") and use the
 * pattern functions in [Enter] / [Exit], which swap transforms for plain fades automatically.
 */
object Motion {
    object Duration {
        /** Immediate (reduced motion, or state that must not animate). */
        const val Instant: Int = 0
        /** Fades, color changes, ripples. */
        const val Short: Int = 120
        /** Container transforms, popovers. */
        const val Medium: Int = 250
        /** Approximate settle time of [Spring.standard]; use for tween fallbacks of it. */
        const val Standard: Int = 350
        /** Library → editor "open notebook" shared element. */
        const val Long: Int = 400
        /** Pen idle time before the auto-hidden toolbar returns to full opacity. */
        const val ToolbarRestoreDelay: Int = 600
    }

    object Easing {
        /** Material 3 "emphasized" curve. */
        val Emphasized: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate: ComposeEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val Linear: ComposeEasing = CubicBezierEasing(0f, 0f, 1f, 1f)
    }

    object Spring {
        private const val StandardDamping = 0.85f
        private const val StandardStiffness = 380f // settles in ~350 ms
        private const val SnappyDamping = 0.7f
        private const val SnappyStiffness = 600f // settles in ~280 ms, small overshoot
        private const val GentleDamping = 0.9f
        private const val GentleStiffness = 200f // ~500 ms, sheets and panels
        private const val BouncyDamping = 0.55f
        private const val BouncyStiffness = 500f

        /** Default for container moves, cards, notebook open/close. */
        fun <T> standard(): FiniteAnimationSpec<T> = spring(StandardDamping, StandardStiffness)

        /** Tool switch, toggles, small popovers, tray lift. */
        fun <T> snappy(): FiniteAnimationSpec<T> = spring(SnappyDamping, SnappyStiffness)

        /** Sheets, panels, page manager, list placement. */
        fun <T> gentle(): FiniteAnimationSpec<T> = spring(GentleDamping, GentleStiffness)

        /** Playful confirmation (e.g. a stroke snapping into a shape). Use rarely. */
        fun <T> bouncy(): FiniteAnimationSpec<T> = spring(BouncyDamping, BouncyStiffness)

        /** Positional variants for slide transitions. */
        fun offsetStandard(): FiniteAnimationSpec<IntOffset> = spring(StandardDamping, StandardStiffness)
        fun offsetGentle(): FiniteAnimationSpec<IntOffset> = spring(GentleDamping, GentleStiffness)

        val Standard: FiniteAnimationSpec<Float> = standard()
        val Snappy: FiniteAnimationSpec<Float> = snappy()
        val Gentle: FiniteAnimationSpec<Float> = gentle()
        val Bouncy: FiniteAnimationSpec<Float> = bouncy()
    }

    /** A tween built from tokens, e.g. `Motion.tween(Motion.Duration.Short)`. */
    fun <T> tween(
        durationMillis: Int = Duration.Medium,
        easing: ComposeEasing = Easing.Standard,
        delayMillis: Int = 0,
    ): FiniteAnimationSpec<T> = composeTween(durationMillis, delayMillis, easing)

    /** Fade spec used everywhere a transform is swapped out under reduced motion. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(Duration.Short, Easing.Linear)

    /** Pen tray: the active pen lifts out of the tray. Animate with [Spring.snappy]. */
    object TrayLift {
        val Offset: Dp = (-8).dp
        const val Scale: Float = 1.06f
    }

    /** Toolbar behaviour while the pen is down (see design principles §1). */
    object Toolbar {
        /** Opacity while writing anywhere on the page. */
        const val WritingAlpha: Float = 0.4f
        /** Fade spec into [WritingAlpha] and back. */
        fun <T> fadeSpec(): FiniteAnimationSpec<T> = tween(Duration.Short)
    }

    /** Shared element "notebook cover → first page". Used as the bounds transform spring. */
    object SharedElement {
        fun <T> bounds(): FiniteAnimationSpec<T> = Spring.standard()
        /** Reduced-motion replacement: crossfade only. */
        fun <T> crossfade(): FiniteAnimationSpec<T> = tween(Duration.Medium)
    }

    /** Enter patterns. Each one degrades to a fade when [reducedMotion] is true. */
    object Enter {
        /** Sheets rise from the bottom edge. */
        fun sheet(reducedMotion: Boolean): EnterTransition =
            if (reducedMotion) fadeIn(fade()) else slideInVertically(Spring.offsetGentle()) { it } + fadeIn(fade())

        /** Popover grows from its anchor (pass the anchor's origin). */
        fun popover(reducedMotion: Boolean, origin: TransformOrigin = TransformOrigin.Center): EnterTransition =
            if (reducedMotion) fadeIn(fade()) else scaleIn(Spring.snappy(), PopoverStartScale, origin) + fadeIn(fade())

        /** Content appearing in place (empty states, cards). */
        fun content(reducedMotion: Boolean): EnterTransition =
            if (reducedMotion) fadeIn(fade()) else fadeIn(tween(Duration.Medium)) + scaleIn(Spring.gentle(), ContentStartScale)

        /** Toolbar returning after auto-hide (it slides back in from its docked edge). */
        fun toolbar(reducedMotion: Boolean): EnterTransition =
            if (reducedMotion) fadeIn(fade()) else slideInVertically(Spring.offsetStandard()) { -it / 2 } + fadeIn(fade())
    }

    /** Exit patterns, mirroring [Enter]. Exits are a little faster than enters. */
    object Exit {
        fun sheet(reducedMotion: Boolean): ExitTransition =
            if (reducedMotion) fadeOut(fade()) else slideOutVertically(Spring.offsetStandard()) { it } + fadeOut(fade())

        fun popover(reducedMotion: Boolean, origin: TransformOrigin = TransformOrigin.Center): ExitTransition =
            if (reducedMotion) fadeOut(fade()) else scaleOut(tween(Duration.Short), PopoverStartScale, origin) + fadeOut(fade())

        fun content(reducedMotion: Boolean): ExitTransition = fadeOut(fade())

        fun toolbar(reducedMotion: Boolean): ExitTransition =
            if (reducedMotion) fadeOut(fade()) else slideOutVertically(Spring.offsetStandard()) { -it / 2 } + fadeOut(fade())
    }

    private const val PopoverStartScale = 0.92f
    private const val ContentStartScale = 0.96f
}

/** Convenience: the current reduced-motion flag, e.g. `Motion.Enter.sheet(reducedMotion())`. */
@Composable
@ReadOnlyComposable
fun reducedMotion(): Boolean = BestNoteTheme.reducedMotion
