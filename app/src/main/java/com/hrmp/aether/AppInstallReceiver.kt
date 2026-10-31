package com.tirexnet.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirm) }
                "Confirm installation in Android."
            }
            PackageInstaller.STATUS_SUCCESS -> "TIREXNET updated successfully."
            else -> "Update installation failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    companion object { const val ACTION_RESULT = "com.tirexnet.app.UPDATE_INSTALL_RESULT" }
}
