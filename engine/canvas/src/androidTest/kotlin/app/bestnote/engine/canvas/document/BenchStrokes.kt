package app.bestnote.engine.canvas.document

import android.content.Context
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import org.json.JSONObject

/** Real handwriting for the canvas benchmarks (the lecture_en corpus sample in the test assets). */
internal object BenchStrokes {
    /** The corpus sample repeated over an A4 page, in page points, with the default fountain pen. */
    fun handwriting(context: Context, copiesX: Int, copiesY: Int, pageH: Float = 842f): List<Stroke> {
        val json = JSONObject(context.assets.open("lecture_en.json").bufferedReader().readText())
        val wMm = json.getDouble("width").toFloat()
        val hMm = json.getDouble("height").toFloat()
        val pageW = 595f
        val ptPerMm = minOf(pageW / copiesX / wMm, pageH / copiesY / hMm)
        val brush = checkNotNull(BrushCatalog.brush(Tool.Pen, PenStyle.Fountain, 0xFF1E2433.toInt(), BrushCatalog.DEFAULT_SIZE * 0.3f * 0.5f))
        val raw = json.getJSONArray("strokes")
        val out = ArrayList<Stroke>()
        for (cy in 0 until copiesY) for (cx in 0 until copiesX) {
            val ox = cx * wMm * ptPerMm
            val oy = cy * hMm * ptPerMm
            for (i in 0 until raw.length()) {
                val s = raw.getJSONObject(i)
                if (s.optString("type", "ink") != "ink") continue
                val samples = s.getJSONArray("samples")
                if (samples.length() == 0) continue
                val batch = MutableStrokeInputBatch()
                val t0 = samples.getJSONObject(0).getDouble("t_ms")
                for (j in 0 until samples.length()) {
                    val p = samples.getJSONObject(j)
                    batch.add(
                        InputToolType.STYLUS,
                        ox + p.getDouble("x").toFloat() * ptPerMm,
                        oy + p.getDouble("y").toFloat() * ptPerMm,
                        (p.getDouble("t_ms") - t0).toLong(),
                        CM_PER_PT,
                        p.optDouble("pressure", 0.5).toFloat(),
                        p.optDouble("tiltRad", 0.0).toFloat(),
                        p.optDouble("orientationRad", 0.0).toFloat(),
                    )
                }
                out += Stroke(brush, batch)
            }
        }
        return out
    }

    private const val CM_PER_PT = 2.54f / 72f
}
