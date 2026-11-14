package io.github.kprakz.homeflix

import android.content.Context
import java.security.SecureRandom

/** The app's settings. The server process reads them once when it starts. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("homeflix", Context.MODE_PRIVATE)

    init {
        // First run: a random PIN, so the phone server is never open to the whole Wi-Fi by default.
        if (!prefs.contains("pin")) prefs.edit().putString("pin", newPin()).putBoolean("pinOn", true).apply()
        if (!prefs.contains("secret")) prefs.edit().putString("secret", randomHex(16)).apply()
        // The first test build ticked folders automatically, personal ones included: start over.
        if (prefs.getInt("foldersVersion", 0) < 1) prefs.edit().remove("folders").putInt("foldersVersion", 1).apply()
    }

    var folders: Set<String>
        get() = prefs.getStringSet("folders", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("folders", v).apply()

    var pinOn: Boolean
        get() = prefs.getBoolean("pinOn", true)
        set(v) = prefs.edit().putBoolean("pinOn", v).apply()

    var pin: String
        get() = prefs.getString("pin", null) ?: newPin().also { pin = it }
        set(v) = prefs.edit().putString("pin", v).apply()

    /** Storage limit for converted copies, in GB. */
    var cacheGb: Int
        get() = prefs.getInt("cacheGb", 2)
        set(v) = prefs.edit().putInt("cacheGb", v).apply()

    /** Re-encoding video makes phones hot, so it's off unless the person turns it on. */
    var convertVideo: Boolean
        get() = prefs.getBoolean("convertVideo", false)
        set(v) = prefs.edit().putBoolean("convertVideo", v).apply()

    val port: Int get() = prefs.getInt("port", 8080)
    val secret: String get() = prefs.getString("secret", null)!!

    companion object {
        private val random = SecureRandom()
        fun newPin(): String = (1000 + random.nextInt(9000)).toString()
        private fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
