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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var preference: CameraPreference? = null

    /** The live settings; the defaults apply until the user stores something. */
    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    /** Set once the user changes a value, so a late store read cannot undo it. */
    @Volatile
    private var mdnsTimeoutTouched = false

    @Volatile
    private var keepaliveIntervalTouched = false

    @Volatile
    private var active: ActiveConnection? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var keepaliveJob: Job? = null

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
        val engine = CameraDiscovery(applicationContext)
        discovery = engine
        val prefs = CameraPreference(applicationContext)
        preference = prefs
        watchUsbHotPlug(applicationContext, engine)
        // The stored values are loaded once, in the background: the first discovery
        // uses the defaults and a value that lands later is picked up by the next one.
        scope.launch {
            runCatching {
                prefs.mdnsTimeoutSeconds.collect { stored ->
                    // A value the user just set wins over a slower disk read: the
                    // collector must never revert a choice that was already made.
                    if (stored != null && !mdnsTimeoutTouched) {
                        _settings.update { current ->
                            current.copy(
                                mdnsTimeoutSeconds = CameraSettings.clampMdnsTimeout(stored),
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            runCatching {
                prefs.keepaliveIntervalSeconds.collect { stored ->
                    if (stored != null && !keepaliveIntervalTouched) {
                        _settings.update { current ->
                            current.copy(
                                keepaliveIntervalSeconds = CameraSettings.clampKeepaliveInterval(stored),
                            )
                        }
                    }
                }
            }
        }
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
        val found = engine.discover(mdnsTimeoutMs())
        _devices.value = found
        return found
    }

    /** The mDNS window in milliseconds, from the user's setting. */
    private fun mdnsTimeoutMs(): Long =
        _settings.value.mdnsTimeoutSeconds * 1000L

    /**
     * Store the mDNS timeout (in seconds) and apply it to the next discovery.
     *
     * The clamp lives in [CameraSettings] so the drawer, the store and the probe
     * agree on what is a legal value.
     */
    fun setMdnsTimeoutSeconds(seconds: Int) {
        val clamped = CameraSettings.clampMdnsTimeout(seconds)
        mdnsTimeoutTouched = true
        _settings.value = _settings.value.copy(mdnsTimeoutSeconds = clamped)
        val prefs = preference ?: return
        scope.launch { runCatching { prefs.setMdnsTimeoutSeconds(clamped) } }
    }

    /**
     * Store the keepalive interval (in seconds) and apply it to a live link.
     *
     * A connected network camera restarts its probe schedule with the new interval,
     * so the change is not deferred to the next connection.
     */
    fun setKeepaliveIntervalSeconds(seconds: Int) {
        val clamped = CameraSettings.clampKeepaliveInterval(seconds)
        keepaliveIntervalTouched = true
        _settings.value = _settings.value.copy(keepaliveIntervalSeconds = clamped)
        // The live link picks the new interval up right away, whether or not the
        // value could be persisted.
        val connection = active
        if (connection != null &&
            connection.device.link == io.github.fotlab.fotlab.feature.camera.model.CameraLink.IP
        ) {
            startKeepalive(connection.device)
        }
        val prefs = preference ?: return
        scope.launch { runCatching { prefs.setKeepaliveIntervalSeconds(clamped) } }
    }

    suspend fun connect(device: CameraDevice, preferAlternateGuid: Boolean = false) {
        val context = appContext ?: throw IllegalStateException("CameraCore.prepare() was not called")
        val engine = discovery ?: throw IllegalStateException("CameraCore.prepare() was not called")
        disconnect()
        lastSeenDevice = device
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
            transport.onLinkBroken = { handleLinkBroken(transport.label) }
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
            reconnectJob?.cancel()
            reconnectJob = null
            startKeepalive(device)
            _state.value = CameraConnectionState.Connected(device, profile, identity)
        } catch (error: Throwable) {
            _state.value = CameraConnectionState.Failed(device, error.message ?: error.toString())
            throw error
        }
    }

    suspend fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
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
    suspend fun download(handles: Collection<Long>, target: Uri? = null): List<Uri> {
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
        return runFileOperation { repository -> repository.download(objects, target) }
    }

    /**
     * Creates a folder in the given storage and parent folder.
     *
     * The device decides whether it supports the operation: a camera that refuses answers with
     * a response code, which reaches the screen as an ordinary error.
     */
    suspend fun createFolder(storageId: Long, parent: Long, name: String): Long =
        runFileOperation { repository ->
            repository.createFolder(storageId, parent, name)
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
        keepaliveJob?.cancel()
        keepaliveJob = null
        withContext(Dispatchers.IO) { runCatching { connection.session.close() } }
        _state.value = CameraConnectionState.Failed(
            connection.device,
            error.message ?: error.toString(),
        )
    }

    /**
     * Entry point invoked from the transport's [PtpTransport.onLinkBroken] hook.
     * Unlike [runFileOperation] which catches failures inside an explicit call,
     * the hook fires for failures that happen outside one — for example when the
     * event reader hits a closed socket and nobody was actively transacting.
     *
     * This is **not** on the main suspend path of [connect], so it can run
     * before [active] is assigned; in that case we still tear down the session
     * that [connect] was building and mark the device as failed.
     */
    private fun handleLinkBroken(transportLabel: String) {
        val connection = active
        if (connection != null) {
            val device = connection.device
            scope.launch {
                failLink(
                    connection,
                    CameraLinkException("Link torn down: $transportLabel"),
                )
                // USB detach is handled by the broadcast receiver and should not
                // be auto-retried; IP flakiness (Wi-Fi jitter, camera sleep) is.
                if (device.link == io.github.fotlab.fotlab.feature.camera.model.CameraLink.IP) {
                    startReconnect(device)
                }
            }
        } else if (_state.value is CameraConnectionState.Connecting) {
            scope.launch {
                val st = _state.value as? CameraConnectionState.Connecting ?: return@launch
                _state.value = CameraConnectionState.Failed(st.device, "Link torn down during connect: $transportLabel")
            }
        }
    }

    private fun transportFor(
        engine: CameraDiscovery,
        device: CameraDevice,
        profile: CameraProfile,
    ): PtpTransport = when (device) {
        is CameraDevice.Usb -> UsbPtpTransport(device.device, engine.usbDiscovery)
        is CameraDevice.Ip -> when (device.transportKind) {
            io.github.fotlab.fotlab.feature.camera.discovery.TransportKind.HTTP_SONY ->
                throw UnsupportedOperationException(
                    "HTTP_SONY transport for ${device.displayName} (${device.host}) is scaffolded; " +
                        "full browse/download wiring follows",
                )
            io.github.fotlab.fotlab.feature.camera.discovery.TransportKind.PTP -> {
                val port = if (device.port == 15740) profile.initPort else device.port
                IpPtpTransport(device.host, port, profile)
            }
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

    /**
     * Retry the most recently failed or disconnected device.
     *
     * Returns `true` if the device was found and a reconnect was kicked off,
     * `false` if there is nothing to retry or the device vanished before we
     * could start. The caller can observe the state flow to follow the attempt.
     */
    fun retry(): Boolean {
        val last = lastSeenDevice ?: return false
        if (reconnectJob?.isActive == true) return false
        val isConnecting = _state.value is CameraConnectionState.Connecting
        if (isConnecting) return false
        startReconnect(last)
        return true
    }

    private fun startReconnect(device: CameraDevice) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectJob = scope.launch {
            var attempt = 0
            var currentDelay = RECONNECT_BASE_DELAY_MS
            while (attempt < MAX_RECONNECT_ATTEMPTS && this.isActive) {
                attempt++
                delay(currentDelay)
                // User may have explicitly disconnected or switched device while
                // we were backing off; bail out cleanly if so.
                if (active != null) return@launch
                val state = _state.value
                if (state is CameraConnectionState.Idle) return@launch
                if (!this.isActive) return@launch
                runCatching { connect(device) }
                    .onSuccess { return@launch }
                    .onFailure { currentDelay *= 2 }
            }
            reconnectJob = null
        }
    }

    /**
     * Probe the link periodically so an idle connection tears the socket down
     * instead of being left half-open. We send [PtpSession.deviceInfo] every
     * keepalive interval — it is a cheap read that the PTP spec allows after
     * OpenSession, and the session guards against OpenSession racing.
     *
     * The interval is the user's setting ([CameraSettings]); it is read before every
     * probe, so a change takes effect on the next cycle even if the job was not
     * restarted.
     *
     * This only runs on IP links; USB is physical and does not need it.
     */
    private fun startKeepalive(device: CameraDevice) {
        keepaliveJob?.cancel()
        if (device.link != io.github.fotlab.fotlab.feature.camera.model.CameraLink.IP) return
        keepaliveJob = scope.launch {
            while (this.isActive && active != null) {
                delay(_settings.value.keepaliveIntervalSeconds * 1000L)
                val connection = active ?: return@launch
                try {
                    connection.session.deviceInfo()
                } catch (error: Throwable) {
                    runCatching { failLink(connection, CameraLinkException("Keepalive probe failed: ${error.message}", error)) }
                    return@launch
                }
            }
        }
    }

    /** Last device we ever saw (from failed or disconnected attempts) — used by [retry]. */
    @Volatile
    private var lastSeenDevice: CameraDevice? = null

    private const val MAX_RECONNECT_ATTEMPTS: Int = 5
    private const val RECONNECT_BASE_DELAY_MS: Long = 1_000L
}
