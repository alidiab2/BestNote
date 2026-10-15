package app.bestnote.engine.ink

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode

/**
 * Builds the androidx.ink [BrushFamily] for each [InkFamily] from [BrushTuning]. Families are
 * native objects, so they are built once on first use and shared.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object BrushFamilies {
    private val cache = HashMap<InkFamily, BrushFamily>()

    @Synchronized
    operator fun get(family: InkFamily): BrushFamily = cache.getOrPut(family) { build(family) }

    private fun build(family: InkFamily): BrushFamily = when (family) {
        InkFamily.Fountain -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        InkFamily.Pencil -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        InkFamily.Ballpoint -> custom(
            BrushTip(
                behaviors = listOf(
                    pressureToSize(BrushTuning.Ballpoint.MIN_PRESSURE_WIDTH, BrushTuning.Ballpoint.RESPONSE_MS),
                    predictionFadeOut,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.BrushPen -> custom(
            BrushTip(
                behaviors = listOf(
                    pressureToSize(BrushTuning.BrushPen.MIN_PRESSURE_WIDTH, BrushTuning.BrushPen.RESPONSE_MS, EasingFunction.Predefined.EASE_IN),
                    taper(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE),
                    taper(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE),
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
        BrushFamily.builder().setCoat(BrushCoat(tip, paint)).build()

    /** Fades predicted (not yet real) stroke segments, like androidx.ink's stock pens. */
    private val predictionFadeOut = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.OPACITY_MULTIPLIER,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = 0.3f,
            input = SourceNode(SourceNode.Source.PREDICTED_TIME_ELAPSED_IN_SECONDS, 0f, 0.024f, OutOfRange.CLAMP),
        ),
    )

    private fun pressureToSize(minWidth: Float, responseMs: Long, curve: EasingFunction = EasingFunction.Predefined.LINEAR) =
        BrushBehavior(
            TargetNode(
                target = TargetNode.Target.SIZE_MULTIPLIER,
                targetModifierRangeStart = minWidth,
                targetModifierRangeEnd = 1f,
                input = DampingNode(
                    dampOver = ProgressDomain.TIME_IN_SECONDS,
                    strength = responseMs / 1000f,
                    input = ResponseNode(curve, SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, OutOfRange.CLAMP)),
                ),
            ),
        )

    private fun taper(source: SourceNode.Source) = BrushBehavior(
        TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = BrushTuning.BrushPen.TAPER_TIP_WIDTH,
            targetModifierRangeEnd = 1f,
            input = ResponseNode(
                EasingFunction.Predefined.EASE_OUT,
                SourceNode(source, 0f, BrushTuning.BrushPen.TAPER_LENGTH, OutOfRange.CLAMP),
            ),
        ),
    )
}
