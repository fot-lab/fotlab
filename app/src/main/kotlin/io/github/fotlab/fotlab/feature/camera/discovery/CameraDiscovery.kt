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

    suspend fun discoverIp(): List<CameraDevice.Ip> = network.discover().map { device ->
        CameraDevice.Ip(
            host = device.host,
            port = device.port,
            source = device.source,
            manufacturer = device.manufacturer,
            model = device.model,
            friendlyName = device.name.ifBlank { null },
        )
    }

    suspend fun discover(): List<CameraDevice> = coroutineScope {
        val local = async { discoverUsb() }
        val remote = async { discoverIp() }
        (local.await() as List<CameraDevice>) + remote.await()
    }
}
