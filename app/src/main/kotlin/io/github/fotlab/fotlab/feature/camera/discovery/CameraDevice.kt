package io.github.fotlab.fotlab.feature.camera.discovery

import android.hardware.usb.UsbDevice
import io.github.fotlab.fotlab.feature.camera.model.CameraLink

/**
 * Upper-layer protocol spoken over the link. A USB link always uses PTP; an IP
 * link may speak PTP/IP (the default for Canon, Nikon, Sony wireless) or Sony's
 * HTTP API (`urn:schemas-sony-com:service:ScalarWebAPI:1`) used by older Sony
 * Cyber-shot / Handycam models.
 */
enum class TransportKind { PTP, HTTP_SONY }

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
        /** Sony HTTP API base URL (from SSDP `ActionList_URL`). Non-null implies [transportKind] is [TransportKind.HTTP_SONY]. */
        val actionListUrl: String? = null,
        /** Upper-layer protocol; defaults to PTP/IP, may be HTTP_SONY for older Sony IP cameras. */
        val transportKind: TransportKind = TransportKind.PTP,
    ) : CameraDevice() {
        override val id: String = "ip:$host:$port:$transportKind"
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
    /** Sony HTTP API base URL extracted from SSDP `X_ScalarWebAPI_ActionList_URL`. */
    val actionListUrl: String? = null,
    /** Upper-layer protocol negotiated at discovery time. */
    val transportKind: TransportKind = TransportKind.PTP,
)
