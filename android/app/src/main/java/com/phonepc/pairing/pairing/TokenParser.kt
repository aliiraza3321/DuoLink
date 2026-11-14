package com.phonepc.pairing.pairing

import com.phonepc.pairing.data.PairingPayload
import java.net.InetAddress
import java.net.URLDecoder

object TokenParser {

    /**
     * Parses and strictly validates a pairing URI string.
     * Expected format:
     * phonepair://pair?host=<LAN-IP>&port=3000&token=<TOKEN>&fp=<CERT-SHA256>
     */
    fun parse(rawUri: String): Result<PairingPayload> {
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith("phonepair://pair")) {
            return Result.failure(IllegalArgumentException("Invalid URI scheme. Expected phonepair://pair"))
        }

        return try {
            val host = queryParam(trimmed, "host")
                ?: return Result.failure(IllegalArgumentException("Missing 'host' parameter"))
            val portStr = queryParam(trimmed, "port")
                ?: return Result.failure(IllegalArgumentException("Missing 'port' parameter"))
            val token = queryParam(trimmed, "token")
                ?: return Result.failure(IllegalArgumentException("Missing 'token' parameter"))
            val fingerprint = queryParam(trimmed, "fp")
                ?: return Result.failure(IllegalArgumentException("Missing 'fp' (fingerprint) parameter"))

            // Validate Port
            val port = portStr.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid port number"))
            if (port !in 1..65535) {
                return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
            }

            // Validate Host is Private IP or Localhost
            if (!isPrivateOrLocalIp(host)) {
                return Result.failure(IllegalArgumentException("Host must be a private LAN IPv4 address or localhost"))
            }

            // Validate Token Length (>= 32 chars)
            if (token.length < 32) {
                return Result.failure(IllegalArgumentException("Pairing token must be at least 32 characters"))
            }

            // Validate SHA-256 Fingerprint (64 hex characters)
            val cleanFp = fingerprint.replace(":", "").uppercase()
            if (cleanFp.length != 64 || !cleanFp.all { it in "0123456789ABCDEF" }) {
                return Result.failure(IllegalArgumentException("Fingerprint must be a 64-character SHA-256 hex string"))
            }

            Result.success(
                PairingPayload(
                    host = host,
                    port = port,
                    token = token,
                    fingerprintSha256 = cleanFp
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to parse URI: ${e.message}", e))
        }
    }

    /**
     * Reads a single query parameter without touching android.net.Uri, so this
     * security-critical validation runs identically on-device and in JVM unit tests.
     */
    private fun queryParam(rawUri: String, name: String): String? {
        val query = rawUri.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            if (key == name) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }
        return null
    }

    /**
     * Checks if an IP is private (RFC 1918) or loopback.
     */
    fun isPrivateOrLocalIp(host: String): Boolean {
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) {
            return true
        }

        val parts = host.split(".")
        if (parts.size != 4) return false

        val intParts = parts.mapNotNull { it.toIntOrNull() }
        if (intParts.size != 4 || intParts.any { it !in 0..255 }) return false

        val p0 = intParts[0]
        val p1 = intParts[1]

        // 10.0.0.0/8
        if (p0 == 10) return true
        // 172.16.0.0/12
        if (p0 == 172 && p1 in 16..31) return true
        // 192.168.0.0/16
        if (p0 == 192 && p1 == 168) return true

        return false
    }
}
