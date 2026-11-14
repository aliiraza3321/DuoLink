package com.phonepc.pairing.data

/**
 * Parsed payload from the desktop QR code or manual entry URI.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val token: String,
    val fingerprintSha256: String
)

/**
 * Persisted session credentials stored securely in EncryptedSharedPreferences (Android Keystore).
 */
data class SessionCredentials(
    val deviceId: String,
    val sessionId: String,
    val sessionSecret: String,
    val pcHost: String,
    val pcPort: Int,
    val pcFingerprint: String,
    val pcDeviceName: String
)

/**
 * State of the pairing connection lifecycle.
 */
enum class ConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    AWAITING_CONFIRMATION,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Real-time active permissions granted between the devices.
 */
data class DevicePermissions(
    val allowPhoneToControlPc: Boolean = false,
    val allowPcToControlPhone: Boolean = false,
    val allowFileTransfer: Boolean = true,
    val allowClipboardSync: Boolean = true,
    val allowChat: Boolean = true
)

/**
 * In-flight file transfer tracking.
 */
data class FileTransferInfo(
    val transferId: String,
    val filename: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val isIncoming: Boolean,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/**
 * Chat message model.
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: String,
    val isFromMe: Boolean,
    val timestamp: Long
)

/** Live telemetry pushed by the PC every ~2 seconds. */
data class PcStats(
    val cpu: Int,
    val memPct: Int,
    val memUsedGb: Float,
    val memTotalGb: Float,
    val uptimeSec: Long,
    val host: String,
    // PC disk + GPU usage (reported by the desktop; -1 = not reported yet)
    val diskPct: Int = -1,
    val diskUsedGb: Float = 0f,
    val diskTotalGb: Float = 0f,
    val gpuPct: Int = -1
)

/** PC's reply to a PC_COMMAND. [seq] changes on every result so identical messages still re-trigger the UI. */
data class CommandResult(
    val seq: Long,
    val ok: Boolean,
    val message: String
)
