package com.homeflix.app.discovery

import android.content.Context
import android.net.ConnectivityManager
import com.homeflix.app.data.DiscoveredServer
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback for computers that don't advertise mDNS (older HOMEFLIX versions, or networks that drop
 * multicast). Probes the local /24 on the default port and keeps the addresses that answer the
 * HOMEFLIX auth API, then [NsdDiscovery]'s results win when both find the same computer.
 */
class LanScanner(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .callTimeout(1_200, TimeUnit.MILLISECONDS)
        .build()

    /** Emits each HOMEFLIX computer as soon as it answers, so results don't wait for the whole scan. */
    fun scan(): Flow<DiscoveredServer> = channelFlow {
        val hosts = localHosts()
        if (hosts.isEmpty()) return@channelFlow
        val gate = Semaphore(64)
        hosts.forEach { host ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    probe(host)?.let { send(it) }
                }
            }
        }
    }

    private fun localHosts(): List<String> {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()
        val local = props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: return emptyList()
        val bytes = local.address
        val octets = bytes.map { it.toInt() and 0xff }
        // Only the /24 around this device, so a large subnet can't turn into a huge scan.
        return (1..254)
            .filter { it != octets[3] }
            .map { "${octets[0]}.${octets[1]}.${octets[2]}.$it" }
    }

    private fun probe(ip: String): DiscoveredServer? {
        val request = Request.Builder()
            .url("http://$ip:$PORT/api/auth")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    ?: return null
                if (json["ok"] == null || json["pin"] == null) return null
                DiscoveredServer(name = "HOMEFLIX at $ip", url = "http://$ip:$PORT")
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PORT = 8080
    }
}
