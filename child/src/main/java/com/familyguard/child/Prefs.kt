package com.familyguard.child

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = sp.getString("deviceId", null)
        set(v) = sp.edit().putString("deviceId", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", "") ?: ""
        set(v) = sp.edit().putString("deviceName", v).apply()

    var configured: Boolean
        get() = sp.getBoolean("configured", false)
        set(v) = sp.edit().putBoolean("configured", v).apply()

    var policyJson: String?
        get() = sp.getString("policy", null)
        set(v) = sp.edit().putString("policy", v).apply()

    var pausedUntil: Long
        get() = sp.getLong("pausedUntil", 0L)
        set(v) = sp.edit().putLong("pausedUntil", v).apply()

    var suspendedByUs: Set<String>
        get() = sp.getStringSet("suspended", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("suspended", v).apply()

    var hiddenByUs: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var restrictionsByUs: Set<String>
        get() = sp.getStringSet("restrictions", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("restrictions", v).apply()

    var autostartDone: Boolean
        get() = sp.getBoolean("autostartDone", false)
        set(v) = sp.edit().putBoolean("autostartDone", v).apply()

    fun clear() = sp.edit().clear().apply()
}
