package com.familyguard.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the request-notification service after the phone boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotifyService.start(context)
    }
}
