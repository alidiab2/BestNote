package app.bestnote.core.storage

import java.security.MessageDigest

/**
 * Names for PDFs inserted into an existing notebook (PAGES-05): a hash of the bytes, so inserting the same
 * PDF twice stores it once. (A notebook made from a PDF keeps its own `assets/source.pdf`.)
 */
object PdfAssets {
    private const val HASH_HEX_CHARS = 32

    /** `assets/pdf-<hash>.pdf` for [bytes]. */
    fun pathFor(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(HASH_HEX_CHARS)
        return "${BnoteFormat.ASSETS_DIR}pdf-$hex.pdf"
    }
}
