package com.homeflix.app.server

import android.content.Context
import java.security.SecureRandom
import kotlin.math.max

/** What a PIN/password can be for the on-device server. */
enum class PinKind(val label: String, val maxLength: Int) {
    PIN4("4-digit", 4),
    PIN6("6-digit", 6),
    PASSWORD("Custom", 32),
}

/** The PIN/password required by the on-device server. Stored only on this phone. */
object ServerPin {
    private const val PREFS = "homeflix_server"
    private const val KEY = "pin"

    private val random = SecureRandom()

    fun get(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }
    }

    /** The PIN Server Mode runs with right now: the stored one, or a fresh random one (saved). */
    fun ensure(context: Context): String {
        get(context)?.let { return it }
        val generated = generate(PinKind.PASSWORD)
        set(context, generated)
        return generated
    }

    fun set(context: Context, pin: String?): Boolean {
        if (pin != null && pin.isNotEmpty() && !isValid(pin)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, pin ?: "")
            .apply()
        return true
    }

    fun generate(kind: PinKind): String {
        val numeric = "0123456789"
        if (kind != PinKind.PASSWORD) {
            return generateFrom(numeric, kind.maxLength, minLetters = 0, minDigits = 0)
        }
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz"
        val digits = "0123456789"
        val alphabet = letters + digits
        var candidate: String
        do {
            candidate = generateFrom(alphabet, 12, minLetters = 1, minDigits = 1)
        } while (!isValid(candidate))
        return candidate
    }

    private fun generateFrom(alphabet: String, length: Int, minLetters: Int, minDigits: Int): String {
        val chars = CharArray(max(length, 1))
        for (i in chars.indices) chars[i] = alphabet[random.nextInt(alphabet.length)]
        val value = String(chars)
        if (minLetters == 0 && minDigits == 0) return value
        // guarantee at least one letter and one digit as required for PASSWORD
        if (minLetters > 0 && value.none(Char::isLetter)) return generateFrom(alphabet, length, minLetters, minDigits)
        if (minDigits > 0 && value.none(Char::isDigit)) return generateFrom(alphabet, length, minLetters, minDigits)
        return value
    }

    fun isValid(pin: String, kind: PinKind? = null): Boolean = pin.matches(Regex(kind.validationPattern()))

    fun kindOf(pin: String): PinKind {
        if (pin.length == 4 && pin.all(Char::isDigit)) return PinKind.PIN4
        if (pin.length == 6 && pin.all(Char::isDigit)) return PinKind.PIN6
        return PinKind.PASSWORD
    }

    private fun PinKind?.validationPattern(): String = when (this) {
        PinKind.PIN4 -> "^\\d{4}\$"
        PinKind.PIN6 -> "^\\d{6}\$"
        PinKind.PASSWORD -> "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9]{6,32}\$"
        null -> "^\\d{4,8}\$"
    }
}
