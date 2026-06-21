package dev.marco.ancsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the bridge after a reboot so it runs in the background unattended. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                AncsService.start(context)
        }
    }
}
