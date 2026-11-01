package com.familyguard.parent

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest

/** Owner PIN (and optional fingerprint) that protects the parent app itself. */
object AppLock {
    private const val PREFS = "app_lock"
    private const val RELOCK_AFTER_MS = 30_000L

    @Volatile private var unlockedAt = 0L
    @Volatile private var backgroundAt = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("app:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(ctx: Context) = !sp(ctx).getString("pin", null).isNullOrEmpty()

    fun setPin(ctx: Context, pin: String) = sp(ctx).edit().putString("pin", hash(pin)).apply()

    fun checkPin(ctx: Context, pin: String) = sp(ctx).getString("pin", null) == hash(pin)

    fun biometricEnabled(ctx: Context) = sp(ctx).getBoolean("bio", false)

    fun setBiometric(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("bio", on).apply()

    /** "Forgot PIN": remove the PIN and sign the owner out of the dashboard. */
    fun reset(ctx: Context) = sp(ctx).edit().remove("pin").putBoolean("bio", false).putBoolean("signOut", true).apply()

    fun consumeSignOut(ctx: Context): Boolean {
        val v = sp(ctx).getBoolean("signOut", false)
        if (v) sp(ctx).edit().putBoolean("signOut", false).apply()
        return v
    }

    fun markUnlocked() {
        unlockedAt = SystemClock.elapsedRealtime()
        backgroundAt = 0L
    }

    fun markBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    /** True when the app must show the lock screen now. */
    fun isLocked(ctx: Context): Boolean {
        if (!hasPin(ctx)) return true // first launch: create the PIN
        if (unlockedAt == 0L) return true
        val bg = backgroundAt
        return bg != 0L && SystemClock.elapsedRealtime() - bg > RELOCK_AFTER_MS
    }
}
