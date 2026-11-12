package io.github.kprakz.homeflix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/**
 * Watches the phone's temperature and battery while it serves. When the phone runs hot, or the
 * battery is low and not charging, converting pauses (plain streaming carries on). Writes the state
 * to heat.json, which the server reads and the app shows.
 *
 * Goes mainly by the battery temperature, which every phone reports reliably: pauses at 42 °C and
 * resumes under 39 °C, so it doesn't flip on and off. Android's own thermal status is only trusted
 * when the battery agrees: some phones (a OnePlus 6T, for one) report "severe" at 35 °C all day.
 * "Critical" and above always pause.
 */
class HeatWatcher(private val context: Context, private val onChange: (State) -> Unit = {}) {

    data class State(val thermal: Int, val batteryPct: Int, val charging: Boolean, val tempC: Float, val reason: String?) {
        /** A word for the app: Cool, Warm or Hot. */
        val label: String
            get() = when {
                thermal >= PowerManager.THERMAL_STATUS_CRITICAL || tempC >= 42f -> "Hot"
                tempC >= 37f -> "Warm"
                else -> "Cool"
            }
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private var thermal = PowerManager.THERMAL_STATUS_NONE
    private var batteryPct = 100
    private var charging = true
    private var tempC = 30f
    private var hot = false
    private var lowBattery = false
    private var last: State? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermal = status
        update()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = readBattery(intent)
    }

    fun start() {
        thermal = power.currentThermalStatus
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        update()
    }

    fun stop() {
        power.removeThermalStatusListener(thermalListener)
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        charging = plugged != 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (temp > 0) tempC = temp / 10f
        update()
    }

    private fun update() {
        val critical = thermal >= PowerManager.THERMAL_STATUS_CRITICAL
        val severeAndWarm = thermal >= PowerManager.THERMAL_STATUS_SEVERE && tempC >= 38f
        hot = critical || severeAndWarm || if (hot) tempC >= 39f else tempC >= 42f
        lowBattery = if (lowBattery) !charging && batteryPct < 20 else !charging && batteryPct <= 15
        val reason = when {
            hot -> "The phone running HOMEFLIX is hot, so converting is paused until it cools down. Movies that need no converting still play."
            lowBattery -> "The phone running HOMEFLIX is low on battery, so converting is paused. Plug it in to carry on."
            else -> null
        }
        val state = State(thermal, batteryPct, charging, tempC, reason)
        if (state == last) return
        last = state
        write(context, state)
        onChange(state)
    }

    companion object {
        fun file(context: Context) = File(context.filesDir, "heat.json")

        private fun write(context: Context, s: State) {
            val json = JSONObject()
                .put("reason", s.reason ?: JSONObject.NULL)
                .put("thermal", s.thermal)
                .put("batteryPct", s.batteryPct)
                .put("charging", s.charging)
                .put("tempC", s.tempC.toDouble())
            val tmp = File(context.filesDir, "heat.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file(context))
        }

        /** The last state the server process wrote, for the app's screen. */
        fun read(context: Context): State? = runCatching {
            val j = JSONObject(file(context).readText())
            State(j.getInt("thermal"), j.getInt("batteryPct"), j.getBoolean("charging"), j.getDouble("tempC").toFloat(), j.optString("reason").takeIf { !j.isNull("reason") && it.isNotEmpty() })
        }.getOrNull()
    }
}
