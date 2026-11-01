package com.familyguard.child.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Dpm.bootstrap(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "إلغاء هذا الخيار يوقف حماية Zemory Family Guard وسيتم إبلاغ الوالد."
}
