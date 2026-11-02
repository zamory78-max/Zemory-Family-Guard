package com.familyguard.child.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.child.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).configured) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val safe = runCatching { context.packageManager.isSafeMode }.getOrDefault(false)
            Events.log(if (safe) "safemode" else "boot", if (safe) "الوضع الآمن" else "تشغيل الجهاز", "", dedupMs = 2 * 60_000L)
        }
        GuardService.start(context)
    }
}
