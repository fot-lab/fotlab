package io.github.fotlab.fotlab.feature.camera

import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import io.github.fotlab.fotlab.feature.camera.discovery.CameraDevice
import io.github.fotlab.fotlab.feature.camera.discovery.CameraDiscovery
import io.github.fotlab.fotlab.feature.camera.discovery.UsbCameraDiscovery
import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ROOT
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.operation.PtpFileOperations
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfileResolver
import io.github.fotlab.fotlab.feature.camera.protocol.PtpDeviceIdentity
import io.github.fotlab.fotlab.feature.camera.protocol.PtpSession
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransport
import io.github.fotlab.fotlab.feature.camera.transport.IpPtpTransport
import io.github.fotlab.fotlab.feature.camera.transport.UsbPtpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Connection state exposed to any future screen without leaking transports. */
sealed class CameraConnectionState {
    object Idle : CameraConnectionState()
    data class Connecting(val device: CameraDevice) : CameraConnectionState()
    data class Connected(
        val device: CameraDevice,
        val profile: CameraProfile,
        val identity: PtpDeviceIdentity,
    ) : CameraConnectionState()
    data class Failed(val device: CameraDevice, val message: String) : CameraConnectionState()
}

private class ActiveConnection(
    val device: CameraDevice,
    val session: PtpSession,
    val operations: PtpFileOperations,
    val repository: CameraFileRepository,
    val eventJob: Job,
)

/**
 * Process-wide facade for the camera feature.
 *
 * Owns discovery, the single active PTP session and the file repository. The
 * transport and profile are implementation details here: callers see devices,
 * connection state, device events and file operations.
 *
 * Two properties matter for the rest of the feature:
 *
 * - **One session at a time.** [connect] closes whatever was connected before, so
 *   there is never a second half-open link competing for the camera.
 * - **A dead link ends the session.** A transport failure is not a failed
 *   command; it means the camera is gone. The facade converts it into a
 *   [CameraConnectionState.Failed] state and releases the resources, instead of
 *   leaving a session that answers nothing.
 */
object CameraCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var discovery: CameraDiscovery? = null

    @Volatile
    private var active: ActiveConnection? = null

    private val _devices = MutableStateFlow<List<CameraDevice>>(emptyList())
    val devices: StateFlow<List<CameraDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Idle)
    val state: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CameraEvent> = _events.asSharedFlow()

    private val _lastEvent = MutableStateFlow<CameraEvent?>(null)
    val lastEvent: StateFlow<CameraEvent?> = _lastEvent.asStateFlow()

    @Volatile
    private var usbWatcher: android.content.BroadcastReceiver? = null

    /** Call once from the application class. */
    fun prepare(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        discovery = CameraDiscovery(applicationContext)
        watchUsbHotPlug(applicationContext, discovery)
    }

    /**
     * React to a camera being plugged in or pulled out.
     *
     * Attaching one refreshes the device list, so the user does not have to
     * press Refresh; detaching the camera that is currently connected ends the
     * session instead of leaving a link that answers nothing.
     */
    private fun watchUsbHotPlug(context: Context, engine: CameraDiscovery) {
        if (usbWatcher != null) return
        val receiver = engine.usbDiscovery.hotPlugReceiver(
            onAttached = {
                scope.launch {
                    runCatching { discover() }.onFailure {
                        // A device that vanished mid-scan simply yields no new entry.
                    }
                }
            },
            onDetached = { device ->
                scope.launch {
                    val current = active ?: return@launch
                    val connected = current.device as? CameraDevice.Usb ?: return@launch
                    if (connected.device.deviceId == device.deviceId) {
                        disconnect()
                    }
                }
            },
        )
        val filter = android.content.IntentFilter().apply {
            UsbCameraDiscovery.HOT_PLUG_ACTIONS.forEach { addAction(it) }
        }
        context.registerReceiver(receiver, filter)
        usbWatcher = receiver
    }

    suspend fun discover(): List<CameraDevice> {
        val engine = discovery ?: throw IllegalStateException("CameraCore.prepare() was not called")
        val found = engine.discover()
        _devices.value = found
        return found
    }

    suspend fun connect(device: CameraDevice, preferAlternateGuid: Boolean = false) {
        val context = appContext ?: throw IllegalStateException("CameraCore.prepare() was not called")
        val engine = discovery ?: throw IllegalStateException("CameraCore.prepare() was not called")
        disconnect()
        _state.value = CameraConnectionState.Connecting(device)
        try {
            if (device is CameraDevice.Usb) {
                ensureUsbPermission(context, device, engine)
            }
            val profile = CameraProfileResolver.resolve(
                manufacturer = device.manufacturer,
                model = device.model,
                vendorId = device.vendorId,
                link = device.link,
                preferAlternateGuid = preferAlternateGuid,
            )
            val transport = transportFor(engine, device, profile)
            val session = PtpSession(transport, profile.sessionId)
            val identity = session.open()
            val operations = PtpFileOperations(session, profile)
            val eventJob = scope.launch {
                session.events().collect { event ->
                    _lastEvent.value = event
                    _events.emit(event)
                }
            }
            active = ActiveConnection(
                device = device,
                session = session,
                operations = operations,
                repository = CameraFileRepository(context, operations),
                eventJob = eventJob,
            )
            _state.value = CameraConnectionState.Connected(device, profile, identity)
        } catch (error: Throwable) {
            _state.value = CameraConnectionState.Failed(device, error.message ?: error.toString())
            throw error
        }
    }

    suspend fun disconnect() {
        val current = active ?: run {
            _state.value = CameraConnectionState.Idle
            return
        }
        active = null
        current.eventJob.cancel()
        withContext(Dispatchers.IO) { runCatching { current.session.close() } }
        _state.value = CameraConnectionState.Idle
    }

    fun isConnected(): Boolean = active != null

    fun repository(): CameraFileRepository = active?.repository
        ?: throw IllegalStateException("No camera session is connected")

    suspend fun storages() = runFileOperation { repository -> repository.storages() }

    suspend fun objects(
        storageId: Long,
        parent: Long = PTP_PARENT_ROOT,
        filter: PtpMediaFilter = PtpMediaFilter.ALL,
    ) = runFileOperation { repository -> repository.objects(storageId, parent, filter) }

    suspend fun thumbnail(handle: Long) = runFileOperation { repository -> repository.thumbnail(handle) }

    /** Builds grid objects from known handles so the transfer service needs no UI types. */
    suspend fun download(handles: Collection<Long>): List<Uri> {
        val connection = active ?: throw IllegalStateException("No camera session is connected")
        val objects = withContext(Dispatchers.IO) {
            handles.map { handle ->
                val info = connection.operations.objectInfo(handle)
                PtpObject(
                    handle = handle,
                    name = info.name.ifEmpty { handle.toString() },
                    format = info.format,
                    sizeBytes = info.sizeBytes,
                    storageId = info.storageId,
                    parent = info.parent,
                    hasThumbnail = info.thumbBytes > 0,
                )
            }
        }
        return runFileOperation { repository -> repository.download(objects) }
    }

    suspend fun delete(handles: Collection<Long>) =
        runFileOperation { repository -> repository.delete(handles.toList()) }

    suspend fun upload(source: Uri, storageId: Long, parent: Long) =
        runFileOperation { repository -> repository.upload(source, storageId, parent) }

    /**
     * Runs a file operation and reports a dead link as a failed connection.
     *
     * The screen calls file operations directly, so without this a vanished
     * camera would surface as a generic error on one command while the UI kept
     * claiming to be connected. A [CameraLinkException] means the session is
     * gone: it is torn down here and the state becomes
     * [CameraConnectionState.Failed], which the screen renders as "disconnected".
     */
    suspend fun <T> runFileOperation(block: suspend (CameraFileRepository) -> T): T {
        val connection = active ?: throw IllegalStateException("No camera session is connected")
        return try {
            block(connection.repository)
        } catch (error: CameraLinkException) {
            failLink(connection, error)
            throw error
        }
    }

    private suspend fun failLink(connection: ActiveConnection, error: Throwable) {
        if (active !== connection) return
        active = null
        connection.eventJob.cancel()
        withContext(Dispatchers.IO) { runCatching { connection.session.close() } }
        _state.value = CameraConnectionState.Failed(
            connection.device,
            error.message ?: error.toString(),
        )
    }

    private fun transportFor(
        engine: CameraDiscovery,
        device: CameraDevice,
        profile: CameraProfile,
    ): PtpTransport = when (device) {
        is CameraDevice.Usb -> UsbPtpTransport(device.device, engine.usbDiscovery)
        is CameraDevice.Ip -> {
            val port = if (device.port == 15740) profile.initPort else device.port
            IpPtpTransport(device.host, port, profile)
        }
    }

    private suspend fun ensureUsbPermission(
        context: Context,
        device: CameraDevice.Usb,
        engine: CameraDiscovery,
    ) {
        if (engine.usbDiscovery.hasPermission(device.device)) return
        val granted = suspendCancellableCoroutine<Boolean> { continuation ->
            var receiver: android.content.BroadcastReceiver? = null
            receiver = engine.usbDiscovery.permissionReceiver { grantedDevice, result ->
                if (grantedDevice == null || grantedDevice.deviceId == device.device.deviceId) {
                    receiver?.let { runCatching { context.unregisterReceiver(it) } }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            val activeReceiver = receiver ?: return@suspendCancellableCoroutine
            context.registerReceiver(
                activeReceiver,
                IntentFilter(io.github.fotlab.fotlab.feature.camera.discovery.UsbCameraDiscovery.ACTION_USB_PERMISSION),
            )
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(activeReceiver) } }
            engine.usbDiscovery.requestPermission(device.device)
        }
        if (!granted) throw SecurityException("USB permission denied for ${device.displayName}")
    }
}
