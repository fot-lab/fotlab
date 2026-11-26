package io.github.fotlab.fotlab.feature.camera.discovery

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Single entry point for discovery: attached USB devices and network cameras. */
class CameraDiscovery(context: Context) {
    private val usb = UsbCameraDiscovery(context)
    private val network = IpCameraDiscovery(context)

    val usbDiscovery: UsbCameraDiscovery get() = usb
    val ipDiscovery: IpCameraDiscovery get() = network

    suspend fun discoverUsb(): List<CameraDevice.Usb> = usb.attached()

    suspend fun discoverIp(mdnsTimeoutMs: Long = MdnsProbe.DEFAULT_TIMEOUT_MS): List<CameraDevice.Ip> =
        network.discover(mdnsTimeoutMs).map { device ->
        CameraDevice.Ip(
            host = device.host,
            port = device.port,
            source = device.source,
            manufacturer = device.manufacturer,
            model = device.model,
            friendlyName = device.name.ifBlank { null },
            actionListUrl = device.actionListUrl,
            transportKind = device.transportKind,
        )
    }

    /**
     * Discovers USB and network cameras together.
     *
     * [mdnsTimeoutMs] is the window the mDNS probe is given; it is the user's
     * configured value, so the whole scan returns sooner when it is lowered.
     */
    suspend fun discover(mdnsTimeoutMs: Long = MdnsProbe.DEFAULT_TIMEOUT_MS): List<CameraDevice> = coroutineScope {
        val local = async { discoverUsb() }
        val remote = async { discoverIp(mdnsTimeoutMs) }
        (local.await() as List<CameraDevice>) + remote.await()
    }
}
