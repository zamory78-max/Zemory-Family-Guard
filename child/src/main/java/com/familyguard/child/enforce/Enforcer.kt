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

    val SETTINGS_PKGS = setOf("com.android.settings", "com.android.tv.settings", "com.droidlogic.tv.settings", "com.droidlogic.settings")

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

        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
        browsersCache = pm.queryIntentActivities(web, android.content.pm.PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }

    private var browsersCache: Set<String> = emptySet()

    /** Browsers whose address bar we cannot read: blocked in allow-only mode. */
    fun isUnsupportedBrowser(ctx: Context, pkg: String): Boolean {
        refreshCaches(ctx)
        return pkg in browsersCache && !GuardAccessibilityService.BROWSERS.containsKey(pkg)
    }

    fun launchable(ctx: Context): Set<String> {
        refreshCaches(ctx); return launchableCache
    }

    fun essentials(ctx: Context): Set<String> {
        refreshCaches(ctx); return essentialsCache
    }

    private fun overLimit(p: Policy, usage: Map<String, Int>): Set<String> =
        p.limits.filter { (pkg, lim) -> lim > 0 && (usage[pkg] ?: 0) >= lim }.keys

    /**
     * Apps that may still run while apps are shut down (pause, bedtime, or the screen schedule in
     * "allowed apps only" mode), or null when apps are not shut down right now. When several rules
     * apply at once, an app must be allowed by all of them.
     */
    fun downAllowed(p: Policy): Set<String>? {
        var allowed: Set<String>? = null
        if (p.pauseAll || p.isBedtimeNow()) allowed = p.bedAllowed
        if (p.screenRestrictsApps()) allowed = allowed?.intersect(p.screenAllowed) ?: p.screenAllowed
        return allowed
    }

    fun desiredSuspended(ctx: Context, p: Policy, usage: Map<String, Int>): Set<String> {
        if (Guard.isPaused(ctx)) return emptySet()
        val s = HashSet<String>()
        s += p.blocked
        s += overLimit(p, usage)
        downAllowed(p)?.let { s += launchable(ctx) - it }
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
        if (p.allowOnly && isUnsupportedBrowser(ctx, pkg)) return "browser"
        val lim = p.limits[pkg] ?: 0
        if (lim > 0 && (Guard.usageToday[pkg] ?: 0) >= lim) return "limit"
        val down = downAllowed(p)
        if (down != null && pkg !in down && pkg in launchable(ctx)) {
            return if (p.pauseAll) "paused" else if (p.isBedtimeNow()) "bedtime" else "screen"
        }
        return null
    }

    /** App stores and the system screen that installs / uninstalls APKs. */
    val STORE_PKGS = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks",
        "com.huawei.appmarket", "com.heytap.market", "com.oppo.market", "com.vivo.appstore",
        "com.transsion.phoenix", "com.amazon.venezia",
    )
    val INSTALLER_PKGS = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
    )

    fun isPinLocked(ctx: Context, pkg: String): Boolean {
        val p = Guard.policy
        if (p.pinHash.isEmpty()) return false
        if (pkg in p.pinLocked) return true
        val r = p.restrictions
        if (r["lock_settings"] == true && pkg in SETTINGS_PKGS) return true
        // Without Device Owner, Android ignores install restrictions: enforce them with the owner PIN instead.
        if (!Dpm.isOwner(ctx)) {
            if (r["no_install_apps"] == true && (pkg in STORE_PKGS || pkg in INSTALLER_PKGS)) return true
            if ((r["no_install_unknown_sources"] == true || r["no_uninstall_apps"] == true) && pkg in INSTALLER_PKGS) return true
        }
        return false
    }
}
