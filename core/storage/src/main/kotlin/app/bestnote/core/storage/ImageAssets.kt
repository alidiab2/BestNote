package app.bestnote.core.storage

import java.security.MessageDigest

/**
 * Names for picture files inside a notebook (MEDIA-01). The name is a hash of the stored bytes, so the
 * same picture inserted many times is one file per notebook, and two different pictures never collide.
 */
object ImageAssets {
    private const val HASH_HEX_CHARS = 32

    /** `assets/img-<hash>.<ext>` for [bytes]; [ext] is `jpg` or `png`. */
    fun pathFor(bytes: ByteArray, ext: String): String {
        require(ext == "jpg" || ext == "png") { "Unsupported image type '$ext'" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(HASH_HEX_CHARS)
        return "${BnoteFormat.ASSETS_DIR}img-$hex.$ext"
    }
}
