package io.github.kprakz.homeflix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone server. Runs in its own process (":server"): it unpacks server.js, writes its settings,
 * starts Node.js, keeps the CPU and Wi-Fi awake, and watches the phone's heat. Stopping it ends the
 * process (Node.js can't be restarted inside one) and deletes the converted copies.
 */
class ServerService : Service() {
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heat: HeatWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true

        startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        clearConvertedCopies(this) // leftovers from a server that didn't stop cleanly
        val project = unpackServer()
        writeConfig()

        heat = HeatWatcher(this) { s -> updateNotification(s) }.also { it.start() }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homeflix:server").apply { acquire() }
        @Suppress("DEPRECATION") // still the way to keep Wi-Fi at full speed with the screen off
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homeflix:server").apply { acquire() }

        // Node needs a bigger stack than a default thread has.
        Thread(null, {
            NodeRunner.startNode(arrayOf("node", File(project, "main.js").path, filesDir.path))
        }, "node", 8L * 1024 * 1024).start()
        updateNotification(null)
        return START_STICKY
    }

    private fun shutdown() {
        heat?.stop()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        statusFile(this).delete()
        clearConvertedCopies(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    /** Copies server.js and the web player out of the app, when the app has a newer copy. */
    private fun unpackServer(): File {
        val dest = File(filesDir, "nodejs-project")
        val bundled = assets.open("nodejs-project/VERSION").bufferedReader().use { it.readText() }
        val marker = File(dest, "VERSION")
        if (marker.takeIf { it.exists() }?.readText() != bundled) {
            dest.deleteRecursively()
            copyAssets("nodejs-project", dest)
        }
        return dest
    }

    private fun copyAssets(from: String, to: File) {
        val children = assets.list(from).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (child in children) copyAssets("$from/$child", File(to, child))
    }

    private fun writeConfig() {
        val s = Settings(this)
        val libs = applicationInfo.nativeLibraryDir
        val config = JSONObject()
            .put("port", s.port)
            .put("folders", JSONArray(s.folders.toList()))
            .put("minSizeMB", 5)
            .put("folderDepth", 0) // only the ticked folders themselves, never what's inside them
            .put("cacheDir", cacheDir(this).path)
            .put("ffmpeg", "$libs/libffmpeg.so")
            .put("ffprobe", "$libs/libffprobe.so")
            .put("maxCacheGB", s.cacheGb)
            .put("reserveGB", 1)
            .put("videoConvert", s.convertVideo)
            .put("pin", if (s.pinOn) s.pin else JSONObject.NULL)
            .put("secret", s.secret)
        File(filesDir, "config.json").writeText(config.toString())
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_homeflix)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    private fun updateNotification(state: HeatWatcher.State?) {
        val address = Net.lanAddress(this)?.let { "http://$it:${Settings(this).port}" }
        val text = when {
            state?.reason != null -> state.reason
            address != null -> getString(R.string.notif_serving, address)
            else -> getString(R.string.notif_no_wifi)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.github.kprakz.homeflix.STOP"

        fun cacheDir(context: Context) = File(context.cacheDir, "stream")
        fun statusFile(context: Context) = File(context.filesDir, "status.json")

        fun start(context: Context) = context.startForegroundService(Intent(context, ServerService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))

        /** Deletes converted copies (they're as big as the movie); thumbnails and subtitles stay. */
        fun clearConvertedCopies(context: Context) {
            cacheDir(context).listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("hls-") || f.name.endsWith(".mp4") || f.name.endsWith(".part")) f.deleteRecursively()
            }
        }

        fun cacheBytes(context: Context): Long = cacheDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
