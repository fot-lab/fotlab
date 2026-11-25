package io.github.fotlab.fotlab.feature.camera.discovery

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal mDNS query for `_ptp._tcp.local.`, implemented without an extra dependency. */
object MdnsProbe {
    private const val ADDRESS = "224.0.0.251"
    private const val PORT = 5353
    private const val TIMEOUT_MS = 10_000
    private const val ATTEMPTS = 4
    private const val SERVICE = "_ptp._tcp.local."

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1

    suspend fun probe(): List<DiscoveredIpDevice> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, DiscoveredIpDevice>()
        val socket = DatagramSocket().apply { soTimeout = 1_500 }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS.toLong()
        try {
            val target = InetAddress.getByName(ADDRESS)
            var attempt = 0
            while (System.currentTimeMillis() < deadline && attempt < ATTEMPTS) {
                val query = query()
                runCatching { socket.send(DatagramPacket(query, query.size, target, PORT)) }
                attempt++
                if (attempt < ATTEMPTS) delayQuietly((attempt + 1) * 150L)

                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(9_000)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                    val host = packet.address.hostAddress ?: continue
                    parseResponse(packet.data.copyOf(packet.length), host)
                        .forEach { device -> result.putIfAbsent(device.host, device) }
                }
            }
        } finally {
            socket.close()
        }
        result.values.toList()
    }

    private fun delayQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun query(): ByteArray {
        val question = ByteArrayOutputStream().apply {
            write(encodeName(SERVICE))
            writeU16(this, TYPE_PTR)
            writeU16(this, CLASS_IN)
        }.toByteArray()
        val out = ByteBuffer.allocate(12 + question.size).order(ByteOrder.BIG_ENDIAN)
        out.putShort(0) // transaction id
        out.putShort(0) // flags: standard query
        out.putShort(1) // one question
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        out.put(question)
        return out.array()
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trimEnd('.').split('.').forEach { label ->
            if (label.isEmpty()) return@forEach
            out.write(label.length.coerceAtMost(63))
            out.write(label.toByteArray(StandardCharsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun ByteBuffer.readU16(): Int = short.toInt() and 0xFFFF

    private fun parseResponse(raw: ByteArray, observedHost: String): List<DiscoveredIpDevice> {
        if (raw.size < 12) return emptyList()
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        buffer.readU16() // id
        buffer.readU16() // flags
        val questions = buffer.readU16()
        val answers = buffer.readU16()
        val authority = buffer.readU16()
        val additional = buffer.readU16()

        repeat(questions) {
            if (buffer.remaining() < 4) return emptyList()
            val (_, next) = decodeName(raw, buffer.position())
            buffer.position(next + 4)
        }

        val instances = LinkedHashMap<String, String>() // instance -> srv target
        val ports = LinkedHashMap<String, Int>()
        val addresses = LinkedHashMap<String, String>()

        repeat(answers + authority + additional) {
            if (buffer.remaining() < 10) return@repeat
            val (name, afterName) = decodeName(raw, buffer.position())
            buffer.position(afterName)
            val type = buffer.readU16()
            val clazz = buffer.readU16()
            buffer.int // ttl
            val length = buffer.readU16()
            val start = buffer.position()
            if (start + length > raw.size) return@repeat

            if (clazz == CLASS_IN) {
                when (type) {
                    TYPE_PTR -> {
                        val (instance, _) = decodeName(raw, start)
                        if (instance.isNotBlank()) instances.putIfAbsent(instance, instance)
                    }
                    TYPE_SRV -> if (length >= 6) {
                        val port = ((raw[start + 4].toInt() and 0xFF) shl 8) or
                            (raw[start + 5].toInt() and 0xFF)
                        val (target, _) = decodeName(raw, start + 6)
                        ports[name] = port
                        if (name.isNotBlank()) instances[name] = target
                    }
                    TYPE_A -> if (length >= 4) {
                        addresses[name] = (0 until 4).joinToString(".") {
                            (raw[start + it].toInt() and 0xFF).toString()
                        }
                    }
                }
            }
            buffer.position(start + length)
        }

        if (ports.isEmpty() && instances.isEmpty()) return emptyList()
        return instances.keys.map { instance ->
            val target = instances[instance].orEmpty()
            DiscoveredIpDevice(
                host = addresses[target] ?: addresses[instance] ?: observedHost,
                name = instance.substringBefore('.'),
                port = ports[instance] ?: PORT,
                source = "mdns",
            )
        }
    }

    /** DNS name with RFC 1035 compression pointers; returns the name and the next offset. */
    private fun decodeName(raw: ByteArray, start: Int): Pair<String, Int> {
        var position = start
        var next = start
        var jumped = false
        val labels = mutableListOf<String>()
        var guard = 0
        while (guard++ < 64 && position < raw.size) {
            val length = raw[position].toInt() and 0xFF
            if (length and 0xC0 == 0xC0) {
                if (position + 1 >= raw.size) break
                if (!jumped) next = position + 2
                position = ((length and 0x3F) shl 8) or (raw[position + 1].toInt() and 0xFF)
                jumped = true
                continue
            }
            if (!jumped) next = position + 1
            if (length == 0) break
            if (position + 1 + length > raw.size) break
            labels += String(raw, position + 1, length, StandardCharsets.UTF_8)
            position += 1 + length
            if (!jumped) next = position
        }
        return labels.joinToString(".") to next
    }
}
