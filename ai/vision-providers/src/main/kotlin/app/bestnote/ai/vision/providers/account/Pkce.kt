package app.bestnote.ai.vision.providers.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A PKCE verifier and its S256 challenge (RFC 7636). The verifier never leaves the device. */
data class PkcePair(val verifier: String, val challenge: String) {
    override fun toString() = "PkcePair(challenge=$challenge)"
}

/** RFC 7636 PKCE helpers. Pure, so the flow can be tested without a browser. */
object Pkce {
    const val METHOD = "S256"

    private val url = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes → a 43-character base64url verifier (RFC 7636 §4.1 allows 43–128). */
    fun create(random: SecureRandom = SecureRandom()): PkcePair {
        val verifier = randomToken(random, VERIFIER_BYTES)
        return PkcePair(verifier, challenge(verifier))
    }

    /** BASE64URL(SHA-256(ASCII(verifier))) without padding. */
    fun challenge(verifier: String): String =
        url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** An unguessable base64url token (also used for the CSRF `state`). */
    fun randomToken(random: SecureRandom = SecureRandom(), bytes: Int = STATE_BYTES): String =
        url.encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** Whether [verifier] is a legal RFC 7636 verifier (unreserved characters, 43–128 long). */
    fun isValidVerifier(verifier: String) = verifier.length in 43..128 && verifier.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" }

    private const val VERIFIER_BYTES = 32
    private const val STATE_BYTES = 16
}
