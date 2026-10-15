/*
 * Debug-only: handwriting replay (QA-INK). Never compiled into release builds.
 *
 * Parses the stroke files written by tools/handwriting/generate.py and turns them into a dispatch
 * plan: the MotionEvents a real digitizer would deliver, batched per display frame with historical
 * samples, in view pixels. Pure Kotlin so it is unit-tested on the JVM.
 */
package app.bestnote.debug.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min

/** One digitizer sample; x/y in the file's units (mm) or, after [ReplayPlan.build], view pixels. */
data class ReplaySample(
    val x: Float,
    val y: Float,
    val tMs: Double,
    val pressure: Float,
    val tiltRad: Float,
    val orientationRad: Float,
)

data class ReplayStroke(val hover: Boolean, val button: Boolean, val samples: List<ReplaySample>)

data class StrokeFile(val name: String, val widthMm: Float, val heightMm: Float, val strokes: List<ReplayStroke>) {
    companion object {
        fun parse(text: String): StrokeFile {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == "bestnote-strokes") { "not a bestnote-strokes file" }
            val strokes = root.getValue("strokes").jsonArray.map { el ->
                val o = el.jsonObject
                ReplayStroke(
                    hover = o["type"]?.jsonPrimitive?.content == "hover",
                    button = o["button"]?.jsonPrimitive?.boolean ?: false,
                    samples = o.getValue("samples").jsonArray.map { sample(it.jsonObject) },
                )
            }.filter { it.samples.isNotEmpty() }
            return StrokeFile(
                name = root["name"]?.jsonPrimitive?.content ?: "strokes",
                widthMm = root.getValue("width").jsonPrimitive.double.toFloat(),
                heightMm = root.getValue("height").jsonPrimitive.double.toFloat(),
                strokes = strokes,
            )
        }

        private fun sample(o: JsonObject) = ReplaySample(
            x = o.getValue("x").jsonPrimitive.double.toFloat(),
            y = o.getValue("y").jsonPrimitive.double.toFloat(),
            tMs = o.getValue("t_ms").jsonPrimitive.double,
            pressure = o["pressure"]?.jsonPrimitive?.double?.toFloat() ?: 0f,
            tiltRad = o["tiltRad"]?.jsonPrimitive?.double?.toFloat() ?: 0f,
            orientationRad = o["orientationRad"]?.jsonPrimitive?.double?.toFloat() ?: 0f,
        )
    }
}

enum class ReplayAction { Down, Move, Up, HoverEnter, HoverMove, HoverExit }

/**
 * One MotionEvent to dispatch at [atMs] (relative to replay start). [samples] are oldest first: all
 * but the last become historical samples, the last is the event's current position.
 */
data class ReplayEvent(
    val atMs: Double,
    val action: ReplayAction,
    val samples: List<ReplaySample>,
    val button: Boolean,
    val strokeIndex: Int,
)

object ReplayPlan {
    /**
     * @param pxPerMm file mm -> view px.
     * @param frameMs input batching window; a real panel batches samples per vsync (8.33 ms at 120 Hz).
     *   0 = deliver every sample as its own event (unbuffered dispatch).
     * @param timeScale 1 = real time, 2 = twice as slow.
     */
    fun build(
        file: StrokeFile,
        pxPerMm: Float,
        originX: Float,
        originY: Float,
        frameMs: Double,
        timeScale: Double = 1.0,
    ): List<ReplayEvent> {
        val out = ArrayList<ReplayEvent>()
        val t0 = file.strokes.firstOrNull()?.samples?.firstOrNull()?.tMs ?: 0.0
        fun px(s: ReplaySample) = s.copy(
            x = originX + s.x * pxPerMm,
            y = originY + s.y * pxPerMm,
            tMs = (s.tMs - t0) * timeScale,
        )
        file.strokes.forEachIndexed { index, stroke ->
            val samples = stroke.samples.map(::px)
            val (first, move, last) = if (stroke.hover) {
                Triple(ReplayAction.HoverEnter, ReplayAction.HoverMove, ReplayAction.HoverExit)
            } else {
                Triple(ReplayAction.Down, ReplayAction.Move, ReplayAction.Up)
            }
            out += ReplayEvent(samples.first().tMs, first, listOf(samples.first()), stroke.button, index)
            val middle = if (samples.size > 2) samples.subList(1, samples.size - 1) else emptyList()
            var batch = ArrayList<ReplaySample>()
            var frameEnd = Double.NaN
            for (s in middle) {
                if (frameMs <= 0.0) {
                    out += ReplayEvent(s.tMs, move, listOf(s), stroke.button, index)
                    continue
                }
                if (batch.isNotEmpty() && s.tMs > frameEnd) {
                    out += ReplayEvent(frameEnd, move, batch, stroke.button, index)
                    batch = ArrayList()
                }
                if (batch.isEmpty()) frameEnd = (kotlin.math.floor(s.tMs / frameMs) + 1) * frameMs
                batch += s
            }
            if (batch.isNotEmpty()) out += ReplayEvent(frameEnd, move, batch, stroke.button, index)
            if (samples.size > 1) {
                // The framework flushes pending moves before an UP; the UP itself carries the last sample.
                val lastSample = samples.last()
                val at = max(lastSample.tMs, out.last().atMs)
                out += ReplayEvent(at, last, listOf(lastSample), stroke.button, index)
            } else {
                out += ReplayEvent(samples.first().tMs, last, listOf(samples.first()), stroke.button, index)
            }
        }
        return out
    }

    /** Uniform fit scale so the page fits in the view (never enlarges beyond [maxScale]). */
    fun fitScale(file: StrokeFile, pxPerMm: Float, viewW: Float, viewH: Float, margin: Float, maxScale: Float = 1f): Float {
        val sx = (viewW - 2 * margin) / (file.widthMm * pxPerMm)
        val sy = (viewH - 2 * margin) / (file.heightMm * pxPerMm)
        return min(maxScale, min(sx, sy))
    }
}
