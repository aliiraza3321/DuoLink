package com.phonepc.pairing.apps

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * DuoLink's use of the phone's own wireless debugging, with nothing else to
 * install: the user pairs once with the code Android shows, and DuoLink can
 * then start [ShellHost] as the shell user. The shell is what lets an app give
 * another app a display of its own — the thing the app window feature needs —
 * and the connection never leaves this phone (127.0.0.1 only).
 *
 * Wireless debugging turns itself off on a reboot, so [ensureHelper] simply
 * reconnects next time it is asked.
 */
internal object AdbBridge {

    private const val TAG = "AdbBridge"
    private const val PREFS = "app_windows"
    private const val PAIRED = "paired"
    private const val CONNECT_MS = 5000L
    private const val PAIR_MS = 15000L

    private var manager: Manager? = null
    private var helperStream: AdbStream? = null
    private var helperPort = -1
    private var helperToken: String? = null

    /** The phone's own adbd, as DuoLink. */
    internal class Manager(context: Context) : AbsAdbConnectionManager() {
        private val keys = AdbKeys.load(File(context.noBackupFilesDir, "adb"))

        init {
            setApi(Build.VERSION.SDK_INT)
            setHostAddress(InetAddress.getLoopbackAddress().hostAddress)
            setTimeout(8, TimeUnit.SECONDS)
        }

        override fun getPrivateKey() = keys.privateKey
        override fun getCertificate() = keys.certificate
        override fun getDeviceName() = "DuoLink"
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAIRED, false)

    /**
     * Whether Android's wireless debugging switch is on. A paired phone that has
     * the switch off cannot be reached, and its own screen is the only thing that
     * can turn it back on.
     */
    fun wirelessDebuggingOn(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrDefault(false)

    /** Pairs with the code Android's pairing dialog shows. */
    suspend fun pair(context: Context, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val port = mDNS(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, PAIR_MS)
            ?: return@withContext Result.failure(IllegalStateException("nocode"))
        runCatching {
            val connection = manager ?: Manager(context).also { manager = it }
            connection.pair(InetAddress.getLoopbackAddress().hostAddress, port, code.trim())
        }.map {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PAIRED, true).apply()
            Unit
        }
    }

    /** A connected manager, or null (no pairing, or wireless debugging is off). */
    suspend fun ensureConnected(context: Context): Manager? = withContext(Dispatchers.IO) {
        if (!isPaired(context)) return@withContext null
        val connection = manager ?: Manager(context).also { manager = it }
        if (connection.isConnected) return@withContext connection
        val opened = runCatching { connection.connectTls(context, CONNECT_MS) }.getOrElse {
            Log.i(TAG, "wireless debugging not found over mDNS", it)
            false
        }
        if (opened || connection.isConnected) return@withContext connection
        val port = mDNS(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, CONNECT_MS)
            ?: return@withContext null
        runCatching { connection.connect(port) }.onFailure { Log.i(TAG, "can't connect adbd", it) }
        connection.takeIf { it.isConnected }
    }

    /** The helper's loopback port and token, starting [ShellHost] if it isn't running. */
    suspend fun ensureHelper(context: Context): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (helperPort > 0 && helperToken != null) return@withContext helperPort to helperToken!!
        val connection = ensureConnected(context) ?: return@withContext null
        val token = (0 until 24)
            .map { "0123456789abcdef"[java.security.SecureRandom().nextInt(16)] }
            .joinToString("")
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=$apk app_process /system/bin ${ShellHost::class.java.name} $token"
        val stream = runCatching { connection.openStream("shell:$command") }.getOrElse {
            Log.w(TAG, "can't open a shell", it)
            return@withContext null
        }
        helperStream = stream
        val line = runCatching {
            withTimeoutOrNull(8000) {
                stream.openInputStream().bufferedReader().readLine()
            }
        }.getOrNull()
        val port = line?.removePrefix("ready ")?.trim()?.toIntOrNull()
        if (line == null || port == null) {
            Log.w(TAG, "the helper did not report a port (${line ?: "no answer"})")
            runCatching { stream.close() }
            helperStream = null
            return@withContext null
        }
        helperPort = port
        helperToken = token
        port to token
    }

    /** Stops the helper (all app windows close). */
    fun stopHelper() {
        helperPort = -1
        helperToken = null
        runCatching { helperStream?.close() }
        helperStream = null
    }

    /**
     * A connection of its own for one window: the helper takes the token, then
     * `V <pkg> <w> <h> <dpi>` starts the app on a display of its own.
     */
    suspend fun openWindow(context: Context, pkg: String, width: Int, height: Int, dpi: Int): Socket? =
        withContext(Dispatchers.IO) {
            val (port, token) = ensureHelper(context) ?: return@withContext null
            val socket = runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).apply { tcpNoDelay = true }
            }.getOrElse {
                Log.w(TAG, "can't reach the helper", it)
                return@withContext null
            }
            runCatching {
                val out = socket.getOutputStream()
                out.write("$token\n".toByteArray(Charsets.US_ASCII))
                out.write("V $pkg $width $height $dpi\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }.onFailure {
                runCatching { socket.close() }
                return@withContext null
            }
            socket
        }

    /** The port wireless debugging announced for [service], if it shows up soon. */
    private suspend fun mDNS(context: Context, service: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Int?> { continuation ->
                val mdns = AdbMdns(context, service) { _: InetAddress?, port: Int ->
                    if (port > 0 && continuation.isActive) continuation.resume(port)
                }
                continuation.invokeOnCancellation { runCatching { mdns.stop() } }
                mdns.start()
            }
        }
}
