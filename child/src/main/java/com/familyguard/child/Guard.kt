package com.familyguard.child

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Shared in-memory state used by the service, the accessibility service and the VPN. */
object Guard {
    @Volatile var policy: Policy = Policy()
    @Volatile var usageToday: Map<String, Int> = emptyMap()
    @Volatile var vpnRunning: Boolean = false

    /** Apps opened with the parent PIN (PIN-locked apps), until a timestamp. */
    val unlockedUntil = ConcurrentHashMap<String, Long>()

    /** Apps temporarily allowed by the parent PIN (blocked / over limit / bedtime). */
    val overrideUntil = ConcurrentHashMap<String, Long>()

    fun load(ctx: Context) {
        policy = Policy.parse(Prefs(ctx).policyJson)
    }

    fun isPaused(ctx: Context): Boolean = Prefs(ctx).pausedUntil > System.currentTimeMillis()

    fun isUnlocked(pkg: String) = (unlockedUntil[pkg] ?: 0L) > System.currentTimeMillis()

    fun isOverridden(pkg: String) = (overrideUntil[pkg] ?: 0L) > System.currentTimeMillis()

    fun hashPin(pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("fg:$pin".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun checkPin(pin: String): Boolean {
        val h = policy.pinHash
        return h.isNotEmpty() && h.equals(hashPin(pin), ignoreCase = true)
    }
}
