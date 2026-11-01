package com.familyguard.child.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.familyguard.child.sync.Repo

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmdId = intent.getStringExtra(Installer.EXTRA_CMD) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Not Device Owner: let the system ask for confirmation on the device.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            Repo.commandResult(context, cmdId, "waiting", "بانتظار تأكيد على الجهاز")
            return
        }
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Repo.commandResult(context, cmdId, "done", "تم")
        } else {
            Repo.commandResult(context, cmdId, "error", "($status) $msg")
        }
    }
}
