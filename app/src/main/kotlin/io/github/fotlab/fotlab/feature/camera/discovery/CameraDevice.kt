package io.github.fotlab.fotlab.feature.camera.discovery

import android.hardware.usb.UsbDevice
import io.github.fotlab.fotlab.feature.camera.model.CameraLink

/** A camera found by one of the discovery bearers. */
sealed class CameraDevice {
    abstract val id: String
    abstract val displayName: String
    abstract val link: CameraLink
    abstract val vendorId: Int?
    abstract val manufacturer: String?
    abstract val model: String?

    data class Usb(
        val device: UsbDevice,
        override val vendorId: Int,
        val productId: Int,
        override val manufacturer: String? = null,
        override val model: String? = null,
    ) : CameraDevice() {
        override val id: String = "usb:${device.deviceId}"
        override val displayName: String = model ?: manufacturer ?: device.deviceName
        override val link: CameraLink = CameraLink.USB
    }

    data class Ip(
        val host: String,
        val port: Int = 15740,
        val source: String = "manual",
        override val vendorId: Int? = null,
        override val manufacturer: String? = null,
        override val model: String? = null,
        val friendlyName: String? = null,
    ) : CameraDevice() {
        override val id: String = "ip:$host:$port"
        override val displayName: String = friendlyName ?: model ?: manufacturer ?: host
        override val link: CameraLink = CameraLink.IP
    }
}

/** Intermediate IP record produced by a probe before it is turned into a [CameraDevice.Ip]. */
data class DiscoveredIpDevice(
    val host: String,
    val name: String = "",
    val manufacturer: String? = null,
    val model: String? = null,
    val port: Int = 15740,
    val source: String = "network",
)
