package com.homeflix.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.homeflix.app.data.DiscoveredServer
import java.io.IOException
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Finds HOMEFLIX computers on the same Wi-Fi via mDNS (`_homeflix._tcp`), exactly like the
 * Electron shell's bonjour browser. A multicast lock is required on many Android devices.
 */
class NsdDiscovery(context: Context) {
    private val appContext = context.applicationContext

    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            close(IOException("Network discovery isn't available on this device."))
            awaitClose { }
            return@callbackFlow
        }

        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("homeflix-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val found = LinkedHashMap<String, DiscoveredServer>()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        fun snapshot(): List<DiscoveredServer> = synchronized(found) { found.values.toList() }

        val worker = launch(Dispatchers.IO) {
            for (info in queue) {
                var resolved: Resolved? = null
                var attempt = 0
                while (attempt < 3 && resolved == null) {
                    resolved = resolve(nsd, info)
                    if (resolved == null) {
                        attempt++
                        delay(400L * attempt)
                    }
                }
                if (resolved != null) {
                    synchronized(found) { found[resolved.serviceName] = resolved.server }
                    trySend(snapshot())
                }
                // NsdManager allows only one resolve at a time. Let the previous one clear
                // before asking about the next service, or the next call fails immediately.
                delay(150)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(found) { found.remove(serviceInfo.serviceName) }
                trySend(snapshot())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DiscoveryException(errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (err: Exception) {
            close(err)
        }

        awaitClose {
            queue.close()
            worker.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Resolved? =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            cont.invokeOnCancellation { done.set(true) }
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (done.compareAndSet(false, true)) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!done.compareAndSet(false, true)) return
                    val host = serviceInfo.host
                    val rawAddress = host?.hostAddress
                    if (host == null || rawAddress == null || serviceInfo.port <= 0) {
                        cont.resume(null)
                        return
                    }
                    val address = if (host is Inet6Address) "[${rawAddress.substringBefore('%')}]" else rawAddress
                    val attributes = serviceInfo.attributes
                    val name = attributes?.get("name")?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                        ?: serviceInfo.serviceName.substringBefore('.')
                    val version = attributes?.get("version")?.toString(Charsets.UTF_8)
                    cont.resume(
                        Resolved(
                            serviceName = serviceInfo.serviceName,
                            server = DiscoveredServer(
                                name = name,
                                url = "http://$address:${serviceInfo.port}",
                                version = version,
                            ),
                        ),
                    )
                }
            }
            try {
                nsd.resolveService(info, listener)
            } catch (err: Exception) {
                if (done.compareAndSet(false, true)) cont.resume(null)
            }
        }

    private data class Resolved(val serviceName: String, val server: DiscoveredServer)

    private companion object {
        const val SERVICE_TYPE = "_homeflix._tcp."
    }
}

/** A discovery failure from [NsdManager], with a message that is safe to show in the UI. */
class DiscoveryException(errorCode: Int) : IOException(
    when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "A search is already running. Try again in a moment."
        NsdManager.FAILURE_MAX_LIMIT -> "Too many searches are running. Try again in a moment."
        NsdManager.FAILURE_BAD_PARAMETERS -> "Couldn't start the search. Try again."
        else -> "Couldn't search this Wi-Fi for HOMEFLIX. Try again."
    },
)
