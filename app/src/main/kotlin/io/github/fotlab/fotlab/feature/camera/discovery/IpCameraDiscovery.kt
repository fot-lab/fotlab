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
                // A host that answers PTP/IP keeps the PTP transport, even when the
                // SSDP answer also carries Sony's `ActionList_URL`. The HTTP API is
                // not wired into the session yet (`CameraCore.transportFor`), so
                // preferring it would make a camera that works over PTP unconnectable.
                // The HTTP endpoint is still recorded, for the day it is wired up.
                val speaksPtp =
                    device.transportKind == TransportKind.PTP ||
                        existing.transportKind == TransportKind.PTP
                val transportKind = when {
                    speaksPtp -> TransportKind.PTP
                    device.transportKind == TransportKind.HTTP_SONY ||
                        existing.transportKind == TransportKind.HTTP_SONY -> TransportKind.HTTP_SONY
                    else -> existing.transportKind
                }
                byHost[device.host] = existing.copy(
                    name = existing.name.ifBlank { device.name },
                    manufacturer = existing.manufacturer ?: device.manufacturer,
                    model = existing.model ?: device.model,
                    port = if (existing.port != 15740) existing.port else device.port,
                    source = "${existing.source}+${device.source}",
                    actionListUrl = if (existing.actionListUrl != null) existing.actionListUrl else device.actionListUrl,
                    transportKind = transportKind,
                )
            }
        }
        return byHost.values.toList()
    }
}
