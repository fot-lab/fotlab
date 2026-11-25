package io.github.fotlab.fotlab.feature.camera.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Runs the two network probes together and merges their results by host. */
class IpCameraDiscovery(private val context: Context) {

    suspend fun discover(): List<DiscoveredIpDevice> {
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
                val mdns = async { runCatching { MdnsProbe.probe() }.getOrDefault(emptyList()) }
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
                // HTTP_SONY (SSDP + ActionList_URL) wins over a plain PTP mDNS
                // hit on the same host: an older Sony camera may advertise
                // both protocols on the wire, and we want the explicit API.
                val preferHttp =
                    device.transportKind == TransportKind.HTTP_SONY ||
                        existing.transportKind == TransportKind.HTTP_SONY
                byHost[device.host] = existing.copy(
                    name = existing.name.ifBlank { device.name },
                    manufacturer = existing.manufacturer ?: device.manufacturer,
                    model = existing.model ?: device.model,
                    port = if (existing.port != 15740) existing.port else device.port,
                    source = "${existing.source}+${device.source}",
                    actionListUrl = if (existing.actionListUrl != null) existing.actionListUrl else device.actionListUrl,
                    transportKind = if (preferHttp) TransportKind.HTTP_SONY else existing.transportKind,
                )
            }
        }
        return byHost.values.toList()
    }
}
