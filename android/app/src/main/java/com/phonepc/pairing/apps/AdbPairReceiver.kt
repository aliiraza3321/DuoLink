package com.phonepc.pairing.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.phonepc.pairing.service.PairingForegroundService

/**
 * Reads the six digits typed straight into the pairing notification and passes
 * them to the link service, which owns the pairing. Nothing leaves the phone
 * and no screen has to open for the code to be entered.
 */
class AdbPairReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAIR = "com.phonepc.pairing.ADB_PAIR_CODE"
        const val KEY_CODE = "pair_code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // RemoteInput only accepts a mutable PendingIntent, so the reply is
        // read from the intent the system rewrote.
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(KEY_CODE)
            ?.toString()
            ?.filter { it.isDigit() }
            ?.take(6)
            .orEmpty()
        if (code.length != 6) return

        val next = Intent(context, PairingForegroundService::class.java).apply {
            action = PairingForegroundService.ACTION_ADB_PAIR_CODE
            putExtra(PairingForegroundService.EXTRA_PAIR_CODE, code)
        }
        runCatching { ContextCompat.startForegroundService(context, next) }
    }
}
