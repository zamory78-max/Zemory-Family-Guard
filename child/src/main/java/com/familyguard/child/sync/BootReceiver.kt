package com.familyguard.child.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.child.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).configured) GuardService.start(context)
    }
}
