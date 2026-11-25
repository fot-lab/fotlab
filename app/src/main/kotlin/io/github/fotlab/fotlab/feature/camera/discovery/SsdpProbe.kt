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
                val device = DiscoveredIpDevice(
                    host = host,
                    name = description["friendlyName"] ?: "",
                    manufacturer = description["manufacturer"],
                    model = description["modelName"],
                    source = "ssdp",
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
}
