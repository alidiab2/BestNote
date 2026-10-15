package app.bestnote.engine.ink

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.BinaryOpNode
import androidx.ink.brush.behavior.ConstantNode
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.InterpolationNode
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ValueNode

/**
 * Builds the androidx.ink [BrushFamily] for each [InkFamily] from [BrushTuning]. Families are
 * native objects, so they are built once on first use and shared.
 *
 * Every pen gets the same input smoothing ([BrushTuning.Smoothing]) and the same dot rule
 * ([BrushTuning.Dot]): each width-reducing behavior is lifted to full width while the stroke is
 * shorter than a dot, and dots are drawn bolder than the line, so taps leave round dots in every
 * pen, wet and dry alike. Tapers are also lifted for dashes, so short marks keep their body.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object BrushFamilies {
    private val cache = HashMap<InkFamily, BrushFamily>()

    @Synchronized
    operator fun get(family: InkFamily): BrushFamily = cache.getOrPut(family) { build(family) }

    private fun build(family: InkFamily): BrushFamily = when (family) {
        InkFamily.Fountain -> custom(
            BrushTip(
                scaleX = 1f,
                scaleY = BrushTuning.Fountain.NIB_ASPECT,
                cornerRounding = 1f,
                rotationDegrees = BrushTuning.Fountain.NIB_ANGLE_DEG,
                behaviors = listOf(
                    pressureTo(TargetNode.Target.SIZE_MULTIPLIER, BrushTuning.Fountain.MIN_PRESSURE_WIDTH, BrushTuning.Fountain.RESPONSE_MS, EasingFunction.Predefined.EASE_OUT),
                    speedToSize(BrushTuning.Fountain.SLOW_SPEED, BrushTuning.Fountain.FAST_SPEED, BrushTuning.Fountain.FAST_WIDTH),
                    taper(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, BrushTuning.Fountain.TAPER_START, BrushTuning.Fountain.TAPER_TIP_WIDTH),
                    taper(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, BrushTuning.Fountain.TAPER_END, BrushTuning.Fountain.TAPER_TIP_WIDTH),
                    dotBoost,
                    predictionFadeOut,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.Pencil -> custom(
            BrushTip(
                behaviors = listOf(
                    pressureTo(TargetNode.Target.SIZE_MULTIPLIER, BrushTuning.Pencil.MIN_PRESSURE_WIDTH, BrushTuning.Pencil.RESPONSE_MS),
                    pressureTo(TargetNode.Target.OPACITY_MULTIPLIER, BrushTuning.Pencil.MIN_PRESSURE_OPACITY, BrushTuning.Pencil.RESPONSE_MS),
                    tilt(TargetNode.Target.SIZE_MULTIPLIER, BrushTuning.Pencil.TILT_MAX_WIDTH),
                    tilt(TargetNode.Target.OPACITY_MULTIPLIER, BrushTuning.Pencil.TILT_MIN_OPACITY),
                    dotBoost,
                    predictionFadeOut,
                ),
            ),
            BrushPaint(textureLayers = listOf(InkTextures.pencilGrainLayer()), colorFunctions = emptyList()),
        )
        InkFamily.Ballpoint -> custom(
            BrushTip(
                behaviors = listOf(
                    pressureTo(TargetNode.Target.SIZE_MULTIPLIER, BrushTuning.Ballpoint.MIN_PRESSURE_WIDTH, BrushTuning.Ballpoint.RESPONSE_MS),
                    dotBoost,
                    predictionFadeOut,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.BrushPen -> custom(
            BrushTip(
                behaviors = listOf(
                    pressureTo(TargetNode.Target.SIZE_MULTIPLIER, BrushTuning.BrushPen.MIN_PRESSURE_WIDTH, BrushTuning.BrushPen.RESPONSE_MS, EasingFunction.Predefined.EASE_IN),
                    taper(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, BrushTuning.BrushPen.TAPER_LENGTH, BrushTuning.BrushPen.TAPER_TIP_WIDTH),
                    taper(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, BrushTuning.BrushPen.TAPER_LENGTH, BrushTuning.BrushPen.TAPER_TIP_WIDTH),
                    dotBoost,
                    predictionFadeOut,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.Highlighter -> custom(
            BrushTip(
                scaleX = BrushTuning.Highlighter.CHISEL_SCALE_X,
                scaleY = BrushTuning.Highlighter.CHISEL_SCALE_Y,
                cornerRounding = BrushTuning.Highlighter.CHISEL_CORNER_ROUNDING,
            ),
            // DISCARD: a stroke crossing itself keeps one tone.
            BrushPaint(selfOverlap = SelfOverlap.DISCARD),
        )
    }

    /** The [InkFamily] whose family equals [family] (structural equality), or null for foreign families. */
    @Synchronized
    fun identify(family: BrushFamily): InkFamily? = InkFamily.entries.firstOrNull { get(it) == family }

    // androidx.ink 1.1 keeps client family ids library-internal, so families carry no id; the
    // InkFamily key is what storage persists, and identify() maps a family back to it.
    private fun custom(tip: BrushTip, paint: BrushPaint) =
        BrushFamily.builder()
            .setCoat(BrushCoat(tip, paint))
            .setInputModel(BrushFamily.InputModel.SlidingWindowModel(BrushTuning.Smoothing.WINDOW_MS, BrushTuning.Smoothing.UPSAMPLING_HZ))
            .build()

    private fun source(source: SourceNode.Source, start: Float, end: Float) = SourceNode(source, start, end, OutOfRange.CLAMP)

    /**
     * 0 while the stroke is shorter than a dot, 1 once it is at least [BrushTuning.Dot.MAX_LENGTH]
     * long. Traveled + remaining distance is the stroke's length; each source is clamped to
     * [0, MAX_LENGTH] (normalized to 0..1), so their sum reaches 1 exactly when the stroke does.
     * Eased in, so a dot that wobbles a little (hand tremor) still gets nearly the whole lift.
     * Using DISTANCE_REMAINING keeps only the last MAX_LENGTH of the wet stroke volatile.
     */
    private fun longness(): ValueNode = ResponseNode(EasingFunction.Predefined.EASE_IN, strokeLength(BrushTuning.Dot.MAX_LENGTH))

    /** The stroke's length / [length], capped at 1 (see [longness]). */
    private fun strokeLength(length: Float): ValueNode = BinaryOpNode(
        BinaryOpNode.BinaryOp.MIN,
        BinaryOpNode(
            BinaryOpNode.BinaryOp.SUM,
            source(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, length),
            source(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, length),
        ),
        ConstantNode(1f),
    )

    /** 1 for a dot, 0 for a real stroke (see [longness]). */
    private fun dotness(): ValueNode =
        InterpolationNode(InterpolationNode.Interpolation.LERP, longness(), ConstantNode(1f), ConstantNode(0f))

    /**
     * 1 while the stroke is at most [BrushTuning.Dot.DASH_FULL] long, falling linearly to 0 at
     * [BrushTuning.Dot.DASH_LENGTH]: min(1, (1 - length/DASH_LENGTH) * DASH_LENGTH/(DASH_LENGTH - DASH_FULL)).
     */
    private fun dashness(): ValueNode = with(BrushTuning.Dot) {
        BinaryOpNode(
            BinaryOpNode.BinaryOp.MIN,
            BinaryOpNode(
                BinaryOpNode.BinaryOp.PRODUCT,
                InterpolationNode(InterpolationNode.Interpolation.LERP, strokeLength(DASH_LENGTH), ConstantNode(1f), ConstantNode(0f)),
                ConstantNode(DASH_LENGTH / (DASH_LENGTH - DASH_FULL)),
            ),
            ConstantNode(1f),
        )
    }

    /** [value] (0 = thinnest .. 1 = full) lifted to full for dots. */
    private fun liftForDots(value: ValueNode): ValueNode = BinaryOpNode(BinaryOpNode.BinaryOp.MAX, value, dotness())

    /** Dots are drawn [BrushTuning.Dot.SCALE] times the full width, so they read next to letters. */
    private val dotBoost = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = BrushTuning.Dot.SCALE,
            input = dotness(),
        ),
    )

    /** Fades predicted (not yet real) stroke segments, like androidx.ink's stock pens. */
    private val predictionFadeOut = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.OPACITY_MULTIPLIER,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = 0.3f,
            input = source(SourceNode.Source.PREDICTED_TIME_ELAPSED_IN_SECONDS, 0f, 0.024f),
        ),
    )

    /** Pressure maps [target] from [min] (no pressure) to 1 (full pressure), damped over [responseMs]. */
    private fun pressureTo(target: TargetNode.Target, min: Float, responseMs: Long, curve: EasingFunction = EasingFunction.Predefined.LINEAR) =
        BrushBehavior(
            TargetNode(
                target = target,
                targetModifierRangeStart = min,
                targetModifierRangeEnd = 1f,
                input = liftForDots(
                    DampingNode(
                        dampOver = ProgressDomain.TIME_IN_SECONDS,
                        strength = responseMs / 1000f,
                        input = ResponseNode(curve, source(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)),
                    ),
                ),
            ),
        )

    /** Faster writing narrows the line to [fastWidth]; dots keep full width. */
    private fun speedToSize(slow: Float, fast: Float, fastWidth: Float) = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = fastWidth,
            input = BinaryOpNode(
                BinaryOpNode.BinaryOp.PRODUCT,
                DampingNode(ProgressDomain.TIME_IN_SECONDS, 0.02f, source(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, slow, fast)),
                longness(),
            ),
        ),
    )

    /** Width ramps from [tipWidth] at the stroke end ([source] = 0) to full over [length] brush sizes. */
    private fun taper(source: SourceNode.Source, length: Float, tipWidth: Float) = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = tipWidth,
            targetModifierRangeEnd = 1f,
            // Lifted for dashes, not just dots: a short stroke must not be all taper.
            input = BinaryOpNode(
                BinaryOpNode.BinaryOp.MAX,
                ResponseNode(EasingFunction.Predefined.EASE_OUT, source(source, 0f, length)),
                dashness(),
            ),
        ),
    )

    /** Pencil side shading: past [BrushTuning.Pencil.TILT_START_RAD], [target] moves from 1 to [full]. */
    private fun tilt(target: TargetNode.Target, full: Float) = BrushBehavior(
        TargetNode(
            target = target,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = full,
            input = DampingNode(
                ProgressDomain.TIME_IN_SECONDS,
                0.05f,
                ResponseNode(
                    EasingFunction.Predefined.EASE_IN_OUT,
                    source(SourceNode.Source.TILT_IN_RADIANS, BrushTuning.Pencil.TILT_START_RAD, BrushTuning.Pencil.TILT_FULL_RAD),
                ),
            ),
        ),
    )
}
