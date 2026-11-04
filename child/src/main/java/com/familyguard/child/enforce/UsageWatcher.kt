package com.familyguard.child.enforce

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.familyguard.child.Guard
import com.familyguard.child.admin.Dpm
import com.familyguard.child.sync.Events
import com.familyguard.child.ui.LockActivity

/**
 * Fallback enforcement for devices where Android refuses to enable our accessibility service
 * (some TV boxes). It finds the foreground app from usage events (needs "usage access") and shows
 * the lock screen over it, with the same rules as the accessibility service. Showing the lock
 * screen from the background needs the "display over other apps" permission
 * (adb shell appops set com.familyguard.child SYSTEM_ALERT_WINDOW allow).
 */
object UsageWatcher {
    private const val RESUMED = 1
    private const val PAUSED = 2
    private const val SCREEN_OFF = 16
    private const val SHUTDOWN = 26

    private var lastQuery = 0L
    private var fg: String? = null
    private var lastShownPkg = ""
    private var lastShownAt = 0L
    private var lastDbg = 0L
    private var evCount = 0
    private var lastEv = ""

    /** True when this fallback is doing the work (accessibility off, usage access on). */
    fun active(ctx: Context): Boolean = !Dpm.isAccessibilityOn(ctx) && Dpm.hasUsageAccess(ctx)

    fun tick(ctx: Context) {
        if (!active(ctx)) {
            fg = null
            return
        }
        refreshForeground(ctx)
        val pkg = fg
        val t = System.currentTimeMillis()
        if (t - lastDbg > 5_000L) {
            lastDbg = t
            Log.i("ZemoryUW", "fg=$pkg blocked=${Guard.policy.blocked.size} reason=${pkg?.let { Enforcer.blockReason(ctx, it) }} paused=${Guard.isPaused(ctx)} evCount=$evCount lastEv=$lastEv")
        }
        if (pkg == null) return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg == "android") return
        val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && ime.startsWith("$pkg/")) return

        val now = System.currentTimeMillis()
        if (pkg == lastShownPkg && now - lastShownAt < 3_000L) return

        if (Guard.isHoldActive()) {
            remember(pkg, now)
            LockActivity.show(ctx, "", Guard.holdReason())
            return
        }
        val reason = Enforcer.blockReason(ctx, pkg)
        if (reason != null) {
            remember(pkg, now)
            Events.log("block", label(ctx, pkg), reason)
            Log.i("ZemoryUW", "show lock for $pkg ($reason)")
            LockActivity.show(ctx, pkg, reason)
            return
        }
        if (!Guard.isPaused(ctx) && Enforcer.isPinLocked(ctx, pkg) && !Guard.isUnlocked(pkg)) {
            remember(pkg, now)
            LockActivity.show(ctx, pkg, "pin")
        }
    }

    private fun remember(pkg: String, now: Long) {
        lastShownPkg = pkg
        lastShownAt = now
    }

    private fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun refreshForeground(ctx: Context) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 60_000L else lastQuery - 1_000L
        val events = try {
            usm.queryEvents(from, now)
        } catch (_: Exception) {
            null
        } ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            evCount++
            lastEv = "${e.eventType}:$p"
            when (e.eventType) {
                RESUMED -> fg = p
                PAUSED -> if (fg == p) fg = null
                SCREEN_OFF, SHUTDOWN -> fg = null
            }
        }
        lastQuery = now
    }
}
