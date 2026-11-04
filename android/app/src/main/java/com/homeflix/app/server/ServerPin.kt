package com.homeflix.app.server

import android.content.Context

/** The PIN required by the on-device server. Matches the desktop app: 4-8 digits, or off. */
object ServerPin {
    private const val PREFS = "homeflix_server"
    private const val KEY = "pin"
    const val DEFAULT = "1234"

    fun get(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return DEFAULT
        return prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }
    }

    fun isValid(pin: String): Boolean = Regex("^\\d{4,8}$").matches(pin)

    fun set(context: Context, pin: String?): Boolean {
        if (pin != null && !isValid(pin)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, pin ?: "")
            .apply()
        return true
    }
}
