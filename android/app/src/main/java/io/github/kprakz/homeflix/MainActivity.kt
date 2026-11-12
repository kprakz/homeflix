package io.github.kprakz.homeflix

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/** The HOMEFLIX screen: start/stop the phone server and set what it shares and how. */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var shownAddress: String? = null
    private var starting = false

    private val videoPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        find<TextView>(R.id.version).text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"

        find<MaterialButton>(R.id.startStop).setOnClickListener { onStartStop() }
        find<MaterialButton>(R.id.allowVideos).setOnClickListener { requestPermissions(arrayOf(videoPermission), REQ_VIDEOS) }

        val pinOn = find<MaterialSwitch>(R.id.pinOn)
        pinOn.isChecked = settings.pinOn
        pinOn.setOnCheckedChangeListener { _, on -> settings.pinOn = on; showPin(); applySettings() }
        find<MaterialButton>(R.id.newPin).setOnClickListener { settings.pin = Settings.newPin(); showPin(); applySettings() }
        showPin()

        val limits = mapOf(R.id.gb1 to 1, R.id.gb2 to 2, R.id.gb4 to 4, R.id.gb8 to 8)
        val group = find<MaterialButtonToggleGroup>(R.id.cacheLimit)
        group.check(limits.entries.firstOrNull { it.value == settings.cacheGb }?.key ?: R.id.gb2)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked && limits[id] != settings.cacheGb) { settings.cacheGb = limits.getValue(id); applySettings() }
        }

        val convert = find<MaterialSwitch>(R.id.convertVideo)
        convert.isChecked = settings.convertVideo
        convert.setOnCheckedChangeListener { _, on -> settings.convertVideo = on; applySettings() }

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onResume() {
        super.onResume()
        showFolders()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VIDEOS) showFolders()
    }

    // ---------- server ----------

    private data class Status(val running: Boolean, val port: Int?, val error: String?, val cacheBytes: Long)

    private fun readStatus(): Status? = runCatching {
        val f = ServerService.statusFile(this)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 10_000) return null
        val j = JSONObject(f.readText())
        Status(j.optBoolean("running"), if (j.isNull("port")) null else j.getInt("port"), j.optString("error").takeIf { !j.isNull("error") && it.isNotEmpty() }, j.optLong("cacheBytes"))
    }.getOrNull()

    private fun onStartStop() {
        if (readStatus() != null || starting) {
            starting = false
            ServerService.stop(this)
            handler.postDelayed({ refreshStatus() }, 800)
            return
        }
        if (Net.lanAddress(this) == null) return toast(getString(R.string.hint_no_wifi))
        if (settings.folders.isEmpty()) return toast(getString(R.string.hint_no_folders))
        starting = true
        ServerService.start(this)
        refreshStatus()
    }

    /** Settings take effect on the next start, so restart a running server. */
    private fun applySettings() {
        if (readStatus() == null) return
        toast(getString(R.string.restarting))
        ServerService.stop(this)
        starting = true
        handler.postDelayed({ ServerService.start(this) }, 1500)
    }

    private fun refreshStatus() {
        val status = readStatus()
        if (status != null) starting = false
        val ip = Net.lanAddress(this)
        val address = if (status?.running == true && ip != null) "http://$ip:${status.port}" else null

        val title = find<TextView>(R.id.status)
        val hint = find<TextView>(R.id.serverHint)
        val button = find<MaterialButton>(R.id.startStop)
        when {
            status?.error != null -> { title.text = status.error; hint.text = "" }
            address != null -> { title.text = getString(R.string.server_on); hint.text = getString(R.string.hint_on) }
            starting || status != null -> { title.text = getString(R.string.server_starting); hint.text = "" }
            else -> { title.text = getString(R.string.server_off); hint.text = getString(if (ip == null) R.string.hint_no_wifi else R.string.hint_off) }
        }
        title.setTextColor(if (address != null) getColor(R.color.green) else getColor(R.color.text))
        button.text = getString(if (status != null || starting) R.string.stop else R.string.start)

        val addressView = find<TextView>(R.id.address)
        val qr = find<ImageView>(R.id.qr)
        addressView.visibility = if (address != null) View.VISIBLE else View.GONE
        qr.visibility = addressView.visibility
        if (address != null && address != shownAddress) {
            addressView.text = address
            qr.setImageBitmap(qrCode(address, 512))
        }
        shownAddress = address

        val used = status?.cacheBytes ?: ServerService.cacheBytes(this)
        find<TextView>(R.id.cacheText).text = getString(R.string.cache_used, Formatter.formatShortFileSize(this, used))

        val heat = if (status != null) HeatWatcher.read(this) else null
        val heatText = find<TextView>(R.id.heatText)
        if (heat == null) {
            heatText.text = getString(R.string.heat_idle)
            heatText.setTextColor(getColor(R.color.muted))
        } else {
            val line = getString(R.string.heat_line, heat.label, heat.tempC, heat.batteryPct, if (heat.charging) getString(R.string.heat_charging) else "")
            heatText.text = if (heat.reason != null) "$line\n\n${heat.reason}" else line
            heatText.setTextColor(getColor(if (heat.reason != null) R.color.amber else R.color.muted))
        }
    }

    // ---------- PIN ----------

    private fun showPin() {
        find<TextView>(R.id.pinText).text = if (settings.pinOn) getString(R.string.pin_on, settings.pin) else getString(R.string.pin_off)
        find<View>(R.id.newPin).visibility = if (settings.pinOn) View.VISIBLE else View.GONE
    }

    // ---------- folders ----------

    private fun showFolders() {
        val list = find<LinearLayout>(R.id.folders)
        val hint = find<TextView>(R.id.foldersHint)
        val allow = find<View>(R.id.allowVideos)
        list.removeAllViews()
        if (!granted(videoPermission)) {
            hint.text = getString(R.string.folders_permission)
            allow.visibility = View.VISIBLE
            return
        }
        allow.visibility = View.GONE
        val folders = Folders.find(this)
        if (folders.isEmpty()) {
            hint.text = getString(R.string.folders_none)
            return
        }
        hint.text = getString(R.string.folders_hint)
        // Nothing is shared until the person ticks a folder.
        val chosen = settings.folders
        for (f in folders) {
            val box = CheckBox(this)
            val line = getString(R.string.folder_line, f.name, f.count, Formatter.formatShortFileSize(this, f.bytes))
            box.text = if (Folders.isPersonal(f.path)) "$line ${getString(R.string.folder_personal)}" else line
            box.setTextColor(getColor(R.color.text))
            box.isChecked = f.path in chosen
            box.setOnCheckedChangeListener { _, on ->
                settings.folders = if (on) settings.folders + f.path else settings.folders - f.path
                applySettings()
            }
            list.addView(box)
        }
    }

    // ---------- helpers ----------

    private fun qrCode(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun <T : View> find(id: Int): T = findViewById(id)

    companion object {
        private const val REQ_VIDEOS = 1
        private const val REQ_NOTIFY = 2
    }
}
