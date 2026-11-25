package io.github.fotlab.fotlab.feature.camera.discovery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.fotlab.fotlab.feature.camera.model.UsbVendorIds

/** Enumerates attached USB cameras and handles the Android USB permission gate. */
class UsbCameraDiscovery(private val context: Context) {
    private val manager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** All attached devices whose vendor id is in the camera table. */
    fun attached(): List<CameraDevice.Usb> = manager?.deviceList?.values
        ?.filter { UsbVendorIds.CAMERA_VENDORS.contains(it.vendorId) }
        ?.map { device ->
            CameraDevice.Usb(
                device = device,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = UsbVendorIds.label(device.vendorId),
            )
        }
        ?.sortedBy { it.id }
        ?: emptyList()

    fun find(deviceId: Int): CameraDevice.Usb? =
        attached().firstOrNull { it.device.deviceId == deviceId }

    fun hasPermission(device: UsbDevice): Boolean = manager?.hasPermission(device) == true

    /**
     * Ask the system for access. The caller registers [permissionReceiver] while
     * the request is outstanding; the pending intent is immutable because the
     * request may outlive the calling activity.
     */
    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            context.applicationContext,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager?.requestPermission(device, intent)
    }

    /** One-shot receiver factory; unregister it after the callback fires. */
    fun permissionReceiver(onResult: (UsbDevice?, Boolean) -> Unit): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                onResult(device, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

    /**
     * Receiver for hot-plug: a camera that is plugged in or pulled out while the
     * app runs. The attached list is re-read on attach, and the detached device
     * is reported so the caller can drop a session that just went away.
     */
    fun hotPlugReceiver(onAttached: (UsbDevice) -> Unit, onDetached: (UsbDevice) -> Unit): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                } ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device ?: return)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device ?: return)
                }
            }
        }

    fun open(device: UsbDevice): android.hardware.usb.UsbDeviceConnection? =
        manager?.openDevice(device)

    companion object {
        const val ACTION_USB_PERMISSION: String = "io.github.fotlab.fotlab.camera.USB_PERMISSION"

        /** Actions the hot-plug receiver listens for. */
        val HOT_PLUG_ACTIONS: Array<String> = arrayOf(
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED,
        )
    }
}
