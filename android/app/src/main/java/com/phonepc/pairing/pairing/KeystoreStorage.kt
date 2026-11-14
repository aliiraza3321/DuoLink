package com.phonepc.pairing.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phonepc.pairing.data.SessionCredentials
import java.util.UUID

class KeystoreStorage(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "phonepc_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback: In rare key corruption cases, reset and re-create
            context.getSharedPreferences("phonepc_secure_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            EncryptedSharedPreferences.create(
                context,
                "phonepc_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun getOrCreateDeviceId(): String {
        var id = sharedPreferences.getString("device_id", null)
        if (id == null) {
            id = "android-" + UUID.randomUUID().toString().take(12)
            sharedPreferences.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun saveCredentials(creds: SessionCredentials) {
        sharedPreferences.edit()
            .putString("device_id", creds.deviceId)
            .putString("session_id", creds.sessionId)
            .putString("session_secret", creds.sessionSecret)
            .putString("pc_host", creds.pcHost)
            .putInt("pc_port", creds.pcPort)
            .putString("pc_fingerprint", creds.pcFingerprint)
            .putString("pc_device_name", creds.pcDeviceName)
            .apply()
    }

    fun getCredentials(): SessionCredentials? {
        val sessionId = sharedPreferences.getString("session_id", null) ?: return null
        val sessionSecret = sharedPreferences.getString("session_secret", null) ?: return null
        val pcHost = sharedPreferences.getString("pc_host", null) ?: return null
        val pcPort = sharedPreferences.getInt("pc_port", 3000)
        val pcFingerprint = sharedPreferences.getString("pc_fingerprint", null) ?: return null
        val pcDeviceName = sharedPreferences.getString("pc_device_name", "Windows PC") ?: "Windows PC"
        val deviceId = getOrCreateDeviceId()

        return SessionCredentials(
            deviceId = deviceId,
            sessionId = sessionId,
            sessionSecret = sessionSecret,
            pcHost = pcHost,
            pcPort = pcPort,
            pcFingerprint = pcFingerprint,
            pcDeviceName = pcDeviceName
        )
    }

    fun clearCredentials() {
        sharedPreferences.edit()
            .remove("session_id")
            .remove("session_secret")
            .remove("pc_host")
            .remove("pc_port")
            .remove("pc_fingerprint")
            .remove("pc_device_name")
            .apply()
    }
}
