package app.bestnote.engine.ink

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes

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
        InkFamily.Fountain -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1).withId(family)
        InkFamily.Pencil -> StockBrushes.marker(StockBrushes.MarkerVersion.V1).withId(family)
        InkFamily.Ballpoint -> custom(
            family,
            BrushTip(
                behaviors = listOf(
                    pressureToSize(BrushTuning.Ballpoint.MIN_PRESSURE_WIDTH, BrushTuning.Ballpoint.RESPONSE_MS),
                    StockBrushes.predictionFadeOutBehavior,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.BrushPen -> custom(
            family,
            BrushTip(
                behaviors = listOf(
                    pressureToSize(BrushTuning.BrushPen.MIN_PRESSURE_WIDTH, BrushTuning.BrushPen.RESPONSE_MS, EasingFunction.Predefined.EASE_IN),
                    taper(BrushBehavior.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE),
                    taper(BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE),
                    StockBrushes.predictionFadeOutBehavior,
                ),
            ),
            BrushPaint(),
        )
        InkFamily.Highlighter -> custom(
            family,
            BrushTip(
                scaleX = BrushTuning.Highlighter.CHISEL_SCALE_X,
                scaleY = BrushTuning.Highlighter.CHISEL_SCALE_Y,
                cornerRounding = BrushTuning.Highlighter.CHISEL_CORNER_ROUNDING,
            ),
            // DISCARD: a stroke crossing itself keeps one tone.
            BrushPaint(selfOverlap = SelfOverlap.DISCARD),
        )
    }

    private fun custom(family: InkFamily, tip: BrushTip, paint: BrushPaint) =
        BrushFamily.builder().setCoat(tip, paint).setClientBrushFamilyId(family.clientId).build()

    private fun BrushFamily.withId(family: InkFamily) = toBuilder().setClientBrushFamilyId(family.clientId).build()

    private fun pressureToSize(minWidth: Float, responseMs: Long, curve: EasingFunction = EasingFunction.Predefined.LINEAR) =
        BrushBehavior(
            source = BrushBehavior.Source.NORMALIZED_PRESSURE,
            target = BrushBehavior.Target.SIZE_MULTIPLIER,
            sourceValueRangeStart = 0f,
            sourceValueRangeEnd = 1f,
            targetModifierRangeStart = minWidth,
            targetModifierRangeEnd = 1f,
            sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
            responseCurve = curve,
            responseTimeMillis = responseMs,
        )

    private fun taper(source: BrushBehavior.Source) = BrushBehavior(
        source = source,
        target = BrushBehavior.Target.SIZE_MULTIPLIER,
        sourceValueRangeStart = 0f,
        sourceValueRangeEnd = BrushTuning.BrushPen.TAPER_LENGTH,
        targetModifierRangeStart = BrushTuning.BrushPen.TAPER_TIP_WIDTH,
        targetModifierRangeEnd = 1f,
        sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
        responseCurve = EasingFunction.Predefined.EASE_OUT,
    )
}
