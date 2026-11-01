package com.familyguard.child.enforce

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.familyguard.child.Guard
import com.familyguard.child.Policy
import com.familyguard.child.Prefs
import com.familyguard.child.admin.Dpm

/**
 * Decides which apps must be unusable right now (blocked, daily limit reached, bedtime, pause)
 * and applies it with Device Owner suspension. The accessibility service uses the same rules
 * as a second layer.
 */
object Enforcer {
    private const val TAG = "FG-Enforcer"

    val SETTINGS_PKGS = setOf("com.android.settings", "com.android.tv.settings")

    private var cacheAt = 0L
    private var launchableCache: Set<String> = emptySet()
    private var essentialsCache: Set<String> = emptySet()

    private fun refreshCaches(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - cacheAt < 60_000L && launchableCache.isNotEmpty()) return
        cacheAt = now
        val pm = ctx.packageManager
        val l = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER")) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            pm.queryIntentActivities(i, 0).forEach { l += it.activityInfo.packageName }
        }
        launchableCache = l

        val ess = HashSet<String>()
        ess += ctx.packageName
        ess += "com.android.systemui"
        ess += SETTINGS_PKGS
        ess += Dpm.homePackages(ctx)
        ess += setOf(
            "com.android.phone", "com.android.server.telecom",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
        )
        pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).forEach { ess += it.activityInfo.packageName }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { ess += it }
        essentialsCache = ess
    }

    fun launchable(ctx: Context): Set<String> {
        refreshCaches(ctx); return launchableCache
    }

    fun essentials(ctx: Context): Set<String> {
        refreshCaches(ctx); return essentialsCache
    }

    private fun overLimit(p: Policy, usage: Map<String, Int>): Set<String> =
        p.limits.filter { (pkg, lim) -> lim > 0 && (usage[pkg] ?: 0) >= lim }.keys

    private fun allDown(p: Policy) = p.pauseAll || p.isBedtimeNow()

    fun desiredSuspended(ctx: Context, p: Policy, usage: Map<String, Int>): Set<String> {
        if (Guard.isPaused(ctx)) return emptySet()
        val s = HashSet<String>()
        s += p.blocked
        s += overLimit(p, usage)
        if (allDown(p)) s += launchable(ctx) - p.bedAllowed
        s -= essentials(ctx)
        s.removeAll { Guard.isOverridden(it) }
        return s
    }

    @Synchronized
    fun apply(ctx: Context) {
        val p = Guard.policy
        val usage = UsageTracker.today(ctx)
        Guard.usageToday = usage
        if (!Dpm.isOwner(ctx)) return
        val prefs = Prefs(ctx)
        val desired = desiredSuspended(ctx, p, usage)
        val prev = prefs.suspendedByUs
        val d = Dpm.dpm(ctx)
        val a = Dpm.admin(ctx)
        val failed = HashSet<String>()
        if (desired.isNotEmpty()) {
            runCatching { d.setPackagesSuspended(a, desired.toTypedArray(), true) }
                .onSuccess { arr -> arr?.let { failed.addAll(it) } }
                .onFailure { Log.w(TAG, "suspend", it); failed += desired }
        }
        val release = prev - desired
        if (release.isNotEmpty()) runCatching { d.setPackagesSuspended(a, release.toTypedArray(), false) }
        prefs.suspendedByUs = desired - failed
    }

    /** Why [pkg] may not be used now, or null if it is allowed. */
    fun blockReason(ctx: Context, pkg: String): String? {
        if (Guard.isPaused(ctx) || Guard.isOverridden(pkg)) return null
        if (pkg in essentials(ctx)) return null
        val p = Guard.policy
        if (pkg in p.blocked) return "blocked"
        val lim = p.limits[pkg] ?: 0
        if (lim > 0 && (Guard.usageToday[pkg] ?: 0) >= lim) return "limit"
        if (allDown(p) && pkg !in p.bedAllowed && pkg in launchable(ctx)) {
            return if (p.pauseAll) "paused" else "bedtime"
        }
        return null
    }

    fun isPinLocked(pkg: String): Boolean {
        val p = Guard.policy
        if (pkg in p.pinLocked) return true
        return p.restrictions["lock_settings"] == true && pkg in SETTINGS_PKGS
    }
}
