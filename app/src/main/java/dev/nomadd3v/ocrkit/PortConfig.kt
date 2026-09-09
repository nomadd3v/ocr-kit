package dev.nomadd3v.ocrkit

import android.content.Context

/**
 * The port the OCR HTTP server listens on. Stored in the same prefs file as
 * the API key so both survive app restarts; defaults to 5210 (the same
 * default the reference service used, so most callers never need to look).
 */
object PortConfig {

    private const val PREFS_NAME = "ocr_kit_prefs"
    private const val PORT_PREF = "port"
    const val DEFAULT_PORT = 5210
    val VALID_RANGE = 1024..65535

    fun getPort(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(PORT_PREF, DEFAULT_PORT)
        return if (stored in VALID_RANGE) stored else DEFAULT_PORT
    }

    /** Returns true iff [port] was in range and saved. */
    fun setPort(ctx: Context, port: Int): Boolean {
        if (port !in VALID_RANGE) return false
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PORT_PREF, port).apply()
        return true
    }
}
