package com.phonepc.pairing.network

import android.util.Log
import com.google.gson.JsonObject
import okhttp3.*
import okio.ByteString
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

class TlsPinningSocket(
    private val expectedFingerprintSha256: String,
    private val listener: SocketEventListener
) {
    interface SocketEventListener {
        fun onConnected()
        fun onMessage(message: Protocol.Envelope)
        /** Binary video frame from the PC (raw bytes, 9-byte header + JPEG). */
        fun onBinaryFrame(frame: Protocol.BinFrame) {}
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable)
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    @Volatile private var closedByUs = false

    init {
        client = createPinnedClient(expectedFingerprintSha256)
    }

    private fun createPinnedClient(pinnedFingerprint: String): OkHttpClient {
        val cleanExpectedFp = pinnedFingerprint.replace(":", "").uppercase()

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }

                val serverCert = chain[0]
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(serverCert.encoded)
                val hexFingerprint = digest.joinToString("") { "%02X".format(it) }

                // Constant-time comparison against pinned fingerprint
                if (!MessageDigest.isEqual(hexFingerprint.toByteArray(), cleanExpectedFp.toByteArray())) {
                    throw CertificateException(
                        "TLS Pinning Mismatch! Expected: $cleanExpectedFp, Received: $hexFingerprint"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // Security is enforced by exact SHA-256 public key pinning
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSocket
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun connect(host: String, port: Int) {
        val url = "wss://$host:$port"
        val request = Request.Builder().url(url).build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = Protocol.parseEnvelope(text)
                if (envelope != null) {
                    // Auto-reply to heartbeat ping so the connection never drops during confirmation
                    if (envelope.type == Protocol.TYPE_HEARTBEAT_PING) {
                        try {
                            val pong = Protocol.createEnvelope(Protocol.TYPE_HEARTBEAT_PONG, JsonObject())
                            webSocket.send(pong)
                        } catch (_: Exception) {}
                    }
                    listener.onMessage(envelope)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary channel = video frames only. Decoding them here (off the
                // main thread, OkHttp's reader thread) keeps Gson off the hot path;
                // a malformed frame is dropped instead of killing the socket.
                try {
                    val frame = Protocol.decodeBinaryFrame(bytes.toByteArray()) ?: return
                    listener.onBinaryFrame(frame)
                } catch (_: Exception) {
                    // One bad frame must never tear down the link.
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "onClosing code=$code reason='$reason' closedByUs=$closedByUs")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "onClosed code=$code reason='$reason' closedByUs=$closedByUs")
                if (closedByUs) return // stale/deliberate close - don't trigger reconnect
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "onFailure closedByUs=$closedByUs http=${response?.code} cause=${t.javaClass.simpleName}: ${t.message}", t)
                if (closedByUs) return
                listener.onFailure(t)
            }
        })
    }

    fun send(messageJson: String): Boolean {
        return webSocket?.send(messageJson) ?: false
    }

    /**
     * Sends a raw binary frame (video). OkHttp takes a ByteString for binary,
     * which avoids the UTF-8 round trip a String would force on JPEG payload.
     * The bytes are copied so the caller may reuse its buffer immediately.
     */
    fun sendBinary(bytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return ws.send(ByteString.of(*bytes.copyOf()))
    }

    /**
     * Bytes OkHttp still has queued for this socket. Live video uses this as a
     * backpressure signal: when the link stalls, the frame pump drops frames
     * (freshest wins) instead of queueing a backlog that would replay later as
     * a frozen screen followed by a burst of stale frames.
     */
    fun queuedBytes(): Long = try {
        webSocket?.queueSize() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    fun close() {
        closedByUs = true
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (_: Exception) {}
        webSocket = null
    }

    companion object {
        private const val TAG = "TlsPinningSocket"

        /**
         * Computes HMAC-SHA256 proof for reconnect challenges.
         */
        fun computeHmacSha256(secretHex: String, nonceHex: String): String {
            val keyBytes = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
            mac.init(secretKey)
            val hmacBytes = mac.doFinal(nonceHex.toByteArray(Charsets.UTF_8))
            return hmacBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
