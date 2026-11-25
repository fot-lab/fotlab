package io.github.fotlab.fotlab.feature.camera.discovery

import android.util.Xml
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** SSDP M-SEARCH probe for cameras that announce themselves on the local network. */
object SsdpProbe {
    private const val MULTICAST = "239.255.255.250"
    private const val PORT = 1900
    private const val SOCKET_TIMEOUT_MS = 4_400
    private const val LOCATION_TIMEOUT_MS = 2_500

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1",
        "urn:schemas-canon-com:service:MobileConnectedCameraService:1",
        "urn:schemas-sony-com:service:ScalarWebAPI:1",
        "urn:schemas-sony-com:service:DigitalImaging:1",
        "ssdp:all",
    )

    suspend fun probe(): List<DiscoveredIpDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredIpDevice>()
        val socket = DatagramSocket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            timeToLive = 4
        }
        try {
            val address = InetAddress.getByName(MULTICAST)
            repeat(2) { round ->
                SEARCH_TARGETS.forEach { target ->
                    val request = buildSearch(target)
                    val packet = DatagramPacket(request, request.size, address, PORT)
                    runCatching { socket.send(packet) }
                    delay(100)
                }
                if (round == 0) delay(150)
            }
            val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS.toLong()
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(8_192)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                val headers = parseHeaders(
                    String(response.data, response.offset, response.length, StandardCharsets.UTF_8),
                )
                val location = headers["location"] ?: continue
                val host = runCatching { URL(location).host }.getOrNull()
                    ?: response.address.hostAddress
                    ?: continue
                val description = fetchDescription(location)
                val actionListUrl = extractActionListUrl(headers)
                val device = DiscoveredIpDevice(
                    host = host,
                    name = description["friendlyName"] ?: "",
                    manufacturer = description["manufacturer"],
                    model = description["modelName"],
                    source = "ssdp",
                    actionListUrl = actionListUrl,
                    transportKind = if (actionListUrl != null) TransportKind.HTTP_SONY else TransportKind.PTP,
                )
                found.putIfAbsent(host, device)
            }
        } finally {
            socket.close()
        }
        found.values.toList()
    }

    private fun buildSearch(target: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 1\r\n")
        append("ST: $target\r\n")
        append("\r\n")
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val lines = raw.replace("\r", "").split("\n")
        val result = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                result.putIfAbsent(
                    line.substring(0, index).trim().lowercase(),
                    line.substring(index + 1).trim(),
                )
            }
        }
        return result
    }

    private fun fetchDescription(location: String): Map<String, String> {
        val connection = URL(location).openConnection().apply {
            connectTimeout = LOCATION_TIMEOUT_MS
            readTimeout = LOCATION_TIMEOUT_MS
        }
        val parser = Xml.newPullParser()
        connection.getInputStream().use { stream ->
            parser.setInput(stream, "UTF-8")
            val keys = setOf("friendlyName", "manufacturer", "modelName")
            val values = LinkedHashMap<String, String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name in keys) {
                    val key = parser.name
                    val text = parser.nextText()?.trim().orEmpty()
                    if (text.isNotEmpty()) values.putIfAbsent(key, text)
                    event = parser.eventType
                    continue
                }
                event = parser.next()
            }
            return values
        }
    }

    /**
     * Pull the Sony HTTP API base URL out of the SSDP response headers. Sony
     * ScalarWebAPI devices publish multiple `X_ScalarWebAPI_*` headers; the
     * one we need is `X_ScalarWebAPI_ActionList_URL`, which lists the HTTP
     * base where `av:XXXX` SOAP actions live.
     */
    private fun extractActionListUrl(headers: Map<String, String>): String? {
        val direct = headers["x_scalarwebapi_actionlist_url"]
        if (!direct.isNullOrBlank()) return direct.trim()
        // Sony also embeds an XML document in a single X_ScalarWebAPI_DeviceInfo
        // header that contains every service type and its ActionList_URL.
        val deviceInfo = headers["x_scalarwebapi_deviceinfo"]
        if (!deviceInfo.isNullOrBlank()) {
            runCatching { parseDeviceInfoXml(deviceInfo) }.getOrNull()?.let { return it }
        }
        // Some models lowercase or hyphenate the header name differently;
        // fall back to a prefix scan.
        return headers.entries.firstOrNull { (k, v) ->
            k.startsWith("x_scalarwebapi_", ignoreCase = true) &&
                k.contains("actionlist", ignoreCase = true) &&
                v.isNotBlank()
        }?.value?.trim()
    }

    /**
     * Parse the Sony-specific XML that the camera ships in
     * `X_ScalarWebAPI_DeviceInfo`. It contains one `X_ScalarWebAPI_Service`
     * block per endpoint (`camera`, `avContent`, etc.) and we want the URL of
     * whichever one exposes the `camera` service — that is the HTTP base for
     * the JSON-RPC control API.
     */
    private fun parseDeviceInfoXml(xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        var inService = false
        var serviceType: String? = null
        var actionList: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            if (event == XmlPullParser.START_TAG) {
                when {
                    name?.equals("X_ScalarWebAPI_Service", ignoreCase = true) == true -> {
                        inService = true
                        serviceType = null
                        actionList = null
                    }
                    inService && name?.equals("X_ScalarWebAPI_ServiceType", ignoreCase = true) == true ->
                        serviceType = parser.nextText()?.trim()
                    inService && name?.equals("X_ScalarWebAPI_ActionList_URL", ignoreCase = true) == true ->
                        actionList = parser.nextText()?.trim()
                }
            } else if (event == XmlPullParser.END_TAG &&
                name?.equals("X_ScalarWebAPI_Service", ignoreCase = true) == true
            ) {
                if (actionList != null && (serviceType == "camera" || serviceType == null)) {
                    return actionList
                }
                inService = false
            }
            event = parser.next()
        }
        return actionList
    }
}
