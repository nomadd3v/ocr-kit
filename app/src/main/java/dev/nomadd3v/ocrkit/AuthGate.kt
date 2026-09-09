package dev.nomadd3v.ocrkit

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Auth for ocr-kit's HTTP surface.
 *
 * Every route — including /health — requires header `X-Api-Key: <key>` to
 * match the key generated on first access. There is no unauthenticated
 * endpoint: a network scan against this service reveals nothing.
 *
 * The key is 32 random bytes, hex-encoded (64 chars), stored in
 * SharedPreferences("ocr_kit_prefs", MODE_PRIVATE) under "api_key". Never
 * logged.
 */
object AuthGate {

    private const val PREFS_NAME = "ocr_kit_prefs"
    private const val KEY_PREF = "api_key"
    private const val KEY_BYTES = 32

    /**
     * Returns the existing API key, generating and persisting one on first
     * access if absent.
     */
    fun getOrCreateKey(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PREF, null)
        if (existing != null) return existing

        val bytes = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        val generated = toHex(bytes)

        prefs.edit().putString(KEY_PREF, generated).apply()
        return generated
    }

    /**
     * True iff [header] (the raw X-Api-Key value, possibly null/missing)
     * matches the stored key under a constant-time comparison. Callers must
     * map any false result to the same 401 response shape regardless of
     * whether the header was missing or simply wrong.
     */
    fun isAuthorized(ctx: Context, header: String?): Boolean {
        if (header == null) return false
        val expected = getOrCreateKey(ctx)
        return constantTimeEquals(expected.toByteArray(Charsets.UTF_8), header.toByteArray(Charsets.UTF_8))
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        // MessageDigest.isEqual is specified to run in time independent of
        // the contents of the arrays (only length may short-circuit, and
        // both operands are attacker-independent-length here in practice
        // since `expected` is fixed at KEY_BYTES*2 hex chars).
        return MessageDigest.isEqual(a, b)
    }

    private fun toHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(hexChars[v ushr 4])
            out.append(hexChars[v and 0x0F])
        }
        return out.toString()
    }
}
