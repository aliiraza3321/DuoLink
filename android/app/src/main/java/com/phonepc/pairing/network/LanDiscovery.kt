package com.phonepc.pairing.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the PC again after its LAN address changes.
 *
 * A pairing stores the PC's IP as it was at that moment. DHCP gives the PC a
 * different address after a router reboot, a Wi-Fi reconnect or a hotspot swap,
 * and from then on every reconnect dialled an address nobody answers on — so a
 * phone whose socket dropped in the background stayed "disconnected" until it
 * was paired all over again.
 *
 * The PC runs a small UDP responder (desktop/src/discovery.js) that answers
 * only for a device id it has actually paired, and the reply comes from the
 * PC's real address. The caller updates the saved host and dials again. A
 * rogue responder cannot hijack the link: the WebSocket is still pinned to the
 * PC's TLS certificate.
 */
object LanDiscovery {
    private const val TAG = "LanDiscovery"
    private const val PORT = 3001
    private const val PROBE = "DUOLINK_DISCOVER 1 "
    private const val REPLY_MARK = "\"app\":\"DuoLink\""

    /** Current LAN address of the paired PC, or null when nobody answered. */
    suspend fun findPc(deviceId: String, timeoutMs: Long = 2500): String? =
        withContext(Dispatchers.IO) {
            if (deviceId.isBlank()) return@withContext null
            val socket = try {
                DatagramSocket()
            } catch (e: Exception) {
                Log.w(TAG, "no udp socket: ${e.message}")
                return@withContext null
            }
            try {
                socket.broadcast = true
                socket.soTimeout = 500
                val payload = (PROBE + deviceId).toByteArray()
                for (target in targets()) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, target, PORT))
                    } catch (e: Exception) {
                        Log.d(TAG, "probe to $target failed: ${e.message}")
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    if (!text.contains(REPLY_MARK)) continue
                    val host = packet.address?.hostAddress ?: continue
                    Log.i(TAG, "PC found at $host")
                    return@withContext host
                }
                Log.i(TAG, "no PC answered the probe")
                null
            } catch (e: Exception) {
                Log.w(TAG, "discovery failed: ${e.message}")
                null
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

    /** 255.255.255.255 plus every interface's own broadcast address. */
    private fun targets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (ia: InterfaceAddress in nic.interfaceAddresses) {
                    ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {}
        return out.toList()
    }
}
