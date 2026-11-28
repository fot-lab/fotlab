package io.github.fotlab.fotlab.feature.camera.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Runs the two network probes together and merges their results by host. */
class IpCameraDiscovery(private val context: Context) {

    /**
     * Runs the SSDP and mDNS probes concurrently and merges their results.
     *
     * [mdnsTimeoutMs] is the window the mDNS query is given to collect answers; the
     * SSDP probe keeps its own, shorter, socket timeouts. A shorter mDNS window makes
     * the whole discovery return sooner, which is what the user trades away when they
     * lower the setting: fewer chances for a slow camera to answer.
     */
    suspend fun discover(mdnsTimeoutMs: Long = MdnsProbe.DEFAULT_TIMEOUT_MS): List<DiscoveredIpDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("camera-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        return try {
            coroutineScope {
                val ssdp = async { runCatching { SsdpProbe.probe() }.getOrDefault(emptyList()) }
                val mdns = async { runCatching { MdnsProbe.probe(mdnsTimeoutMs) }.getOrDefault(emptyList()) }
                merge(ssdp.await() + mdns.await())
            }
        } finally {
            lock?.let { runCatching { if (it.isHeld) it.release() } }
        }
    }

    fun manual(host: String, port: Int = 15740): DiscoveredIpDevice =
        DiscoveredIpDevice(host = host.trim(), port = port, source = "manual")

    private fun merge(devices: List<DiscoveredIpDevice>): List<DiscoveredIpDevice> {
        val byHost = LinkedHashMap<String, DiscoveredIpDevice>()
        devices.forEach { device ->
            val existing = byHost[device.host]
            if (existing == null) {
                byHost[device.host] = device
            } else {
                // TransportKind resolution:
                // - HTTP_SONY wins unconditionally when any source published a
                //   ScalarWebAPI ActionList_URL — that is the camera telling us
                //   its HTTP API endpoint, which is an authoritative claim.
                // - PTP is the fallback when no HTTP endpoint is known.
                //
                // We deliberately do NOT prefer PTP over HTTP: a host may answer
                // DigitalImaging in SSDP (an UPnP service type) without actually
                // speaking PTP/IP over TCP; the ActionList_URL, by contrast,
                // names a concrete HTTP endpoint that exists.
                val mergedActionListUrl = existing.actionListUrl ?: device.actionListUrl
                val transportKind = when {
                    mergedActionListUrl != null -> TransportKind.HTTP_SONY
                    device.transportKind == TransportKind.HTTP_SONY ||
                        existing.transportKind == TransportKind.HTTP_SONY -> TransportKind.HTTP_SONY
                    else -> TransportKind.PTP
                }
                byHost[device.host] = existing.copy(
                    name = existing.name.ifBlank { device.name },
                    manufacturer = existing.manufacturer ?: device.manufacturer,
                    model = existing.model ?: device.model,
                    port = if (existing.port != 15740) existing.port else device.port,
                    source = "${existing.source}+${device.source}",
                    actionListUrl = mergedActionListUrl,
                    transportKind = transportKind,
                )
            }
        }
        return byHost.values.toList()
    }
}
