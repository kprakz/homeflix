package com.homeflix.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.homeflix.app.MainActivity
import com.homeflix.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class ServerUsage(
    val bytes: Long = 0,
    val seconds: Long = 0,
)

data class WatchEntry(
    val ts: Long = 0,
    val title: String = "",
    val seconds: Long = 0,
    val bytes: Long = 0,
)

data class ServerClient(
    val id: String = "",
    val name: String = "",
    val host: String? = null,
    val client: String? = null,
    val address: String = "",
    val firstSeen: Long = 0,
    val active: Boolean = false,
    val activity: String = "idle",
    val title: String? = null,
    val bytes: Long = 0,
    val seconds: Long = 0,
    val lastSeen: Long = 0,
    val requests: Int = 0,
    val today: ServerUsage = ServerUsage(),
    val thisMonth: ServerUsage = ServerUsage(),
    val thisYear: ServerUsage = ServerUsage(),
    val range: ServerUsage? = null,
    val history: List<WatchEntry> = emptyList(),
)

data class ServerState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val address: String? = null,
    val localAddress: String? = null,
    val pin: String? = null,
    val message: String? = null,
    val error: String? = null,
    val clients: List<ServerClient> = emptyList(),
)

class ServerService : Service() {
    companion object {
        const val ACTION_START = "com.homeflix.app.server.START"
        const val ACTION_STOP = "com.homeflix.app.server.STOP"
        private const val CHANNEL_ID = "homeflix_server"
        private const val NOTIFICATION_ID = 42
        private const val PORT = 8080

        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state

        @Volatile
        private var nodeStarted = false

        // Optional custom usage range as a query string, e.g. "?from=2026-01-01&to=2026-12-31".
        @Volatile
        var activityRangeQuery: String? = null

        @Volatile
        private var instance: ServerService? = null

        fun start(context: Context) {
            if (_state.value.running || _state.value.starting) return
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }

        /** Applies folder or PIN changes immediately when the server is running. */
        fun configChanged(context: Context) {
            instance?.reloadConfig()
        }
    }

    private val stopping = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activityThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> begin()
        }
        return START_STICKY
    }

    private fun begin() {
        startForegroundCompat(buildNotification("Starting HOMEFLIX server…"))
        if (stopping.get() || _state.value.running || _state.value.starting) return
        acquireLocks()
        _state.value = ServerState(starting = true, pin = ServerPin.get(this), message = "Starting the embedded server…")

        val workDir = File(filesDir, "nodejs-project")
        val nativeDir = applicationInfo.nativeLibraryDir
        val ffmpeg = File(nativeDir, "libffmpeg_exec.so").absolutePath
        val ffprobe = File(nativeDir, "libffprobe_exec.so").absolutePath

        Thread({
            try {
                if (!nodeStarted) {
                    prepareProject(workDir, ffmpeg, ffprobe)
                    nodeStarted = true
                    Thread({
                        try {
                            val code = NativeNode.startNode(
                                arrayOf("node", File(workDir, "android-server.js").absolutePath),
                                workDir.absolutePath,
                                ffmpeg,
                                ffprobe,
                            )
                            nodeStarted = false
                            if (!stopping.get() && _state.value.running) {
                                _state.update {
                                    it.copy(
                                        starting = false,
                                        running = false,
                                        error = "The embedded runtime stopped unexpectedly (Node exit $code).",
                                        clients = emptyList(),
                                    )
                                }
                            }
                        } catch (err: Throwable) {
                            nodeStarted = false
                            _state.update {
                                it.copy(
                                    starting = false,
                                    running = false,
                                    error = err.message ?: err.javaClass.simpleName,
                                )
                            }
                        }
                    }, "homeflix-node").start()
                }

                if (!waitForControl()) {
                    if (!stopping.get()) {
                        _state.update {
                            it.copy(
                                starting = false,
                                running = false,
                                error = "The embedded runtime did not start. Check the logcat tag HOMEFLIX_NODE.",
                                clients = emptyList(),
                            )
                        }
                    }
                    return@Thread
                }
                if (stopping.get()) return@Thread
                postControl("/start")
                waitUntilReady()
            } catch (err: Throwable) {
                if (!stopping.get()) {
                    _state.update {
                        it.copy(
                            starting = false,
                            running = false,
                            error = err.message ?: err.javaClass.simpleName,
                        )
                    }
                }
            }
        }, "homeflix-server-up").start()
    }

    private fun waitForControl(): Boolean {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !stopping.get()) {
            if (controlRequest("GET", "/status")) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !stopping.get()) {
            if (probe()) {
                val lan = lanAddress()
                _state.value = ServerState(
                    running = true,
                    starting = false,
                    address = "http://${lan ?: "127.0.0.1"}:$PORT",
                    localAddress = "http://127.0.0.1:$PORT",
                    pin = ServerPin.get(this),
                )
                updateNotification("Serving your library at ${lan ?: "127.0.0.1"}:$PORT")
                startActivityPolling()
                return
            }
            Thread.sleep(500)
        }
        if (!stopping.get()) {
            _state.update {
                it.copy(
                    starting = false,
                    running = false,
                    error = "The server did not answer on port $PORT. Check the logcat tag HOMEFLIX_NODE.",
                    clients = emptyList(),
                )
            }
        }
    }

    private fun postControl(path: String): Boolean = controlRequest("POST", path)

    private fun controlRequest(method: String, path: String): Boolean = try {
        val connection = URL("http://127.0.0.1:18081$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 700
        connection.readTimeout = 1500
        connection.requestMethod = method
        if (method == "POST") {
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
        }
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun probe(): Boolean = try {
        val connection = URL("http://127.0.0.1:$PORT/api/hello").openConnection() as HttpURLConnection
        connection.connectTimeout = 700
        connection.readTimeout = 700
        connection.requestMethod = "GET"
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun startActivityPolling() {
        if (activityThread?.isAlive == true) return
        activityThread = Thread({
            while (!stopping.get() && _state.value.running) {
                val clients = fetchActivity()
                if (clients != null && !stopping.get() && _state.value.running) {
                    _state.update { it.copy(clients = clients) }
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "homeflix-server-activity").apply {
            isDaemon = true
            start()
        }
    }

    private fun fetchActivity(): List<ServerClient>? = try {
        val query = activityRangeQuery?.orEmpty() ?: ""
        val connection = URL("http://127.0.0.1:18081/activity$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 700
        connection.readTimeout = 1500
        connection.requestMethod = "GET"
        if (connection.responseCode != 200) {
            connection.disconnect()
            null
        } else {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(parseClient(item))
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun parseClient(item: JSONObject): ServerClient {
        fun usage(obj: JSONObject?) = obj?.let { ServerUsage(it.optLong("bytes"), it.optLong("seconds")) } ?: ServerUsage()
        fun optionalText(key: String) = item.optString(key).takeIf { it.isNotBlank() && !item.isNull(key) }
        val history = item.optJSONArray("history")?.let { entries ->
            buildList {
                for (j in 0 until entries.length()) {
                    val entry = entries.optJSONObject(j) ?: continue
                    if (entry.isNull("title") && entry.isNull("id")) continue
                    add(
                        WatchEntry(
                            ts = entry.optLong("ts"),
                            title = entry.optString("title").ifBlank { "?" },
                            seconds = entry.optLong("seconds"),
                            bytes = entry.optLong("bytes"),
                        ),
                    )
                }
            }
        } ?: emptyList()
        return ServerClient(
            id = item.optString("id"),
            name = item.optString("name").ifBlank { item.optString("address") },
            host = optionalText("host"),
            client = optionalText("client"),
            address = item.optString("address"),
            firstSeen = item.optLong("firstSeen"),
            active = item.optBoolean("active"),
            activity = item.optString("activity", "idle"),
            title = optionalText("title"),
            bytes = item.optLong("bytes"),
            seconds = item.optLong("seconds"),
            lastSeen = item.optLong("lastSeen"),
            requests = item.optInt("requests"),
            today = usage(item.optJSONObject("today")),
            thisMonth = usage(item.optJSONObject("thisMonth")),
            thisYear = usage(item.optJSONObject("thisYear")),
            range = item.optJSONObject("range")?.let { usage(it) },
            history = history,
        )
    }

    private fun prepareProject(workDir: File, ffmpeg: String, ffprobe: String) {
        workDir.mkdirs()
        copyAsset("server", workDir)
        val folders = JSONArray()
        ServerFolders.list(this).forEach { folders.put(it) }
        val config = JSONObject()
            .put("port", PORT)
            .put("pin", ServerPin.get(this) ?: JSONObject.NULL)
            .put("folders", folders)
            .put("minSizeMB", 5)
            .put("cacheDir", "cache")
            .put("ffmpeg", ffmpeg)
            .put("ffprobe", ffprobe)
        File(workDir, "config.json").writeText(config.toString(2))
        File(workDir, "cache").mkdirs()
    }

    private fun reloadConfig() {
        Thread({
            val deadline = System.currentTimeMillis() + 30_000
            while (_state.value.starting && System.currentTimeMillis() < deadline && !stopping.get()) {
                Thread.sleep(200)
            }
            if (!_state.value.running || stopping.get()) return@Thread
            _state.update { it.copy(running = false, starting = true, message = "Updating server settings…", clients = emptyList()) }
            val workDir = File(filesDir, "nodejs-project")
            val nativeDir = applicationInfo.nativeLibraryDir
            val ffmpeg = File(nativeDir, "libffmpeg_exec.so").absolutePath
            val ffprobe = File(nativeDir, "libffprobe_exec.so").absolutePath
            try {
                prepareProject(workDir, ffmpeg, ffprobe)
                if (!postControl("/reload")) {
                    _state.update {
                        it.copy(
                            starting = false,
                            error = "Could not apply the change. Stop and start the server to retry.",
                        )
                    }
                    return@Thread
                }
                waitUntilReady()
            } catch (err: Throwable) {
                _state.update {
                    it.copy(
                        starting = false,
                        running = false,
                        error = err.message ?: err.javaClass.simpleName,
                        clients = emptyList(),
                    )
                }
            }
        }, "homeflix-server-folders").start()
    }

    private fun copyAsset(path: String, target: File) {
        val children = assets.list(path)
        if (children.isNullOrEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAsset("$path/$child", File(target, child)) }
    }

    private fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        _state.update { it.copy(starting = false, running = false, message = "Stopping…", clients = emptyList()) }
        Thread({
            val stopped = postControl("/shutdown")
            if (stopped) {
                _state.value = ServerState(message = "Server stopped. Start it again whenever you want.")
            } else {
                _state.update {
                    it.copy(error = "The embedded server did not confirm shutdown. Its files are no longer shared.")
                }
            }
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "homeflix-node-stop").start()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("homeflix-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HOMEFLIX:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HOMEFLIX server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the phone's HOMEFLIX server running."
            },
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HOMEFLIX is sharing this phone")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun lanAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
