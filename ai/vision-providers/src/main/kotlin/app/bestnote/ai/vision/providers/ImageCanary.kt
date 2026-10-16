package app.bestnote.ai.vision.providers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.VisionImage
import java.io.ByteArrayOutputStream

/**
 * The one place that adds the canary strip ([Canary]) to an image before it goes to an AI provider: a thin white
 * strip along the bottom edge with a random 4-digit code in large plain digits. Used for board photos, Tidy and
 * Convert with AI, so a model that drops the image is caught everywhere.
 */
object ImageCanary {
    /** [bitmap] plus the strip, and the code drawn on it. [bitmap] is not modified. */
    fun stamp(bitmap: Bitmap, code: String = Canary.newCode()): Pair<Bitmap, String> {
        val w = bitmap.width
        val strip = Canary.stripHeight(w, bitmap.height)
        val out = Bitmap.createBitmap(w, bitmap.height + strip, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = Canary.textSize(w, strip)
            textAlign = Paint.Align.CENTER
        }
        val baseline = bitmap.height + strip / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(code, w / 2f, baseline, paint)
        return out to code
    }

    /** JPEG of [bitmap] with the strip. */
    fun jpeg(bitmap: Bitmap, quality: Int = 88): VisionImage {
        val (stamped, code) = stamp(bitmap)
        return VisionImage(encode(stamped, Bitmap.CompressFormat.JPEG, quality), "image/jpeg", code).also { if (stamped !== bitmap) stamped.recycle() }
    }

    /** Already encoded [bytes] (e.g. an ink snapshot PNG), decoded, stamped and re-encoded; the original image if undecodable. */
    fun fromEncoded(bytes: ByteArray, mimeType: String): VisionImage {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return VisionImage(bytes, mimeType)
        val (stamped, code) = stamp(bitmap)
        val png = mimeType.endsWith("png")
        val out = encode(stamped, if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90)
        bitmap.recycle()
        stamped.recycle()
        return VisionImage(out, mimeType, code)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.toByteArray()
        }
}
