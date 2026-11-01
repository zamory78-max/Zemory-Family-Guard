package com.familyguard.child.admin

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.familyguard.child.Guard
import com.familyguard.child.Policy
import com.familyguard.child.Prefs
import com.familyguard.child.enforce.GuardAccessibilityService
import com.familyguard.child.vpn.DnsVpnService

/** Everything that needs Device Owner privileges. Each call is a no-op when we are not owner. */
object Dpm {
    private const val TAG = "FG-Dpm"

    /** User restrictions the parent can toggle from the dashboard (UserManager.DISALLOW_* values). */
    val RESTRICTIONS = setOf(
        "no_install_apps",
        "no_uninstall_apps",
        "no_install_unknown_sources",
        "no_factory_reset",
        "no_add_user",
        "no_safe_boot",
        "no_config_wifi",
        "no_config_bluetooth",
        "no_config_date_time",
        "no_config_tethering",
        "no_config_vpn",
        "no_config_private_dns",
        "no_control_apps",
        "no_modify_accounts",
        "no_usb_file_transfer",
        "no_debugging_features",
        "no_sms",
        "no_outgoing_calls",
        "no_share_location",
        "no_adjust_volume",
        "no_config_brightness",
        "no_config_screen_timeout",
    )

    private val CHROMIUM_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.microsoft.emmx",
        "com.brave.browser",
    )

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(ctx: Context): Boolean =
        runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    /** One-time self protection, safe to call repeatedly. */
    fun bootstrap(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        runCatching { d.setUninstallBlocked(a, ctx.packageName, true) }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                d.setPermissionGrantState(
                    a, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        }
        ensureAccessibility(ctx)
    }

    fun applyPolicy(ctx: Context, p: Policy) {
        if (isOwner(ctx)) {
            val prefs = Prefs(ctx)
            val d = dpm(ctx)
            val a = admin(ctx)

            // 1) user restrictions
            val desired = p.restrictions.filter { it.value && it.key in RESTRICTIONS }.keys.toMutableSet()
            if (p.webFilter) {
                desired += "no_config_vpn"
                if (Build.VERSION.SDK_INT >= 29) desired += "no_config_private_dns"
            }
            if (Build.VERSION.SDK_INT < 29) desired -= "no_config_private_dns"
            for (r in desired) runCatching { d.addUserRestriction(a, r) }.onFailure { Log.w(TAG, "add $r", it) }
            for (r in prefs.restrictionsByUs - desired) runCatching { d.clearUserRestriction(a, r) }
            prefs.restrictionsByUs = desired

            runCatching { d.setCameraDisabled(a, p.restrictions["camera_disabled"] == true) }
            runCatching { d.setScreenCaptureDisabled(a, p.restrictions["screen_capture_disabled"] == true) }

            // 2) hidden apps (fully reversible "remove")
            val protectedPkgs = setOf(ctx.packageName) + homePackages(ctx)
            val wantHidden = p.hidden - protectedPkgs
            val nowHidden = HashSet<String>()
            for (pkg in wantHidden) {
                if (runCatching { d.setApplicationHidden(a, pkg, true) }.getOrDefault(false) ||
                    runCatching { d.isApplicationHidden(a, pkg) }.getOrDefault(false)
                ) nowHidden += pkg
            }
            for (pkg in prefs.hiddenByUs - wantHidden) runCatching { d.setApplicationHidden(a, pkg, false) }
            prefs.hiddenByUs = nowHidden

            // 3) browser managed configuration (turn off DNS-over-HTTPS so the filter sees traffic)
            applyBrowserConfig(ctx, p)
        }
        applyVpn(ctx, p)
    }

    private fun applyBrowserConfig(ctx: Context, p: Policy) {
        val b = Bundle()
        if (p.needsVpn) b.putString("DnsOverHttpsMode", "off")
        if (p.webFilter || p.allowOnly) b.putInt("IncognitoModeAvailability", 1)
        if (p.allowOnly) {
            b.putStringArray("URLBlocklist", arrayOf("*"))
            b.putStringArray("URLAllowlist", p.allowedDomains.toTypedArray())
        } else if (p.webFilter && p.blockedDomains.isNotEmpty()) {
            b.putStringArray("URLBlocklist", p.blockedDomains.toTypedArray())
        }
        if (p.safeSearch) {
            b.putBoolean("ForceGoogleSafeSearch", true)
            b.putInt("ForceYouTubeRestrict", 2)
        }
        val d = dpm(ctx)
        for (pkg in CHROMIUM_BROWSERS) runCatching { d.setApplicationRestrictions(admin(ctx), pkg, b) }
    }

    fun applyVpn(ctx: Context, p: Policy) {
        val owner = isOwner(ctx)
        if (p.needsVpn) {
            if (owner) {
                runCatching { dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), ctx.packageName, false) }
                    .onFailure { Log.w(TAG, "always-on vpn", it) }
                if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { dpm(ctx).setGlobalPrivateDnsModeOpportunistic(admin(ctx)) }
                }
            }
            DnsVpnService.start(ctx)
        } else {
            if (owner) runCatching { dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), null, false) }
            DnsVpnService.stop(ctx)
        }
    }

    /** Re-enables our accessibility service if the child turned it off (needs WRITE_SECURE_SETTINGS via adb). */
    fun ensureAccessibility(ctx: Context) {
        if (isAccessibilityOn(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, GuardAccessibilityService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val next = if (cur.isBlank()) me else "$cur:$me"
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }.onFailure { Log.w(TAG, "enable accessibility", it) }
    }

    fun isAccessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any {
            it.startsWith(ctx.packageName + "/") && it.endsWith("GuardAccessibilityService")
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isAdminActive(ctx: Context): Boolean =
        runCatching { dpm(ctx).isAdminActive(admin(ctx)) }.getOrDefault(false)

    fun isIgnoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun hasWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun homePackages(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    fun lockNow(ctx: Context) {
        runCatching { dpm(ctx).lockNow() }
    }

    fun reboot(ctx: Context) {
        if (isOwner(ctx) && Build.VERSION.SDK_INT >= 24) dpm(ctx).reboot(admin(ctx))
    }

    fun enableSystemApp(ctx: Context, pkg: String) {
        val d = dpm(ctx)
        val a = admin(ctx)
        runCatching { d.setApplicationHidden(a, pkg, false) }
        d.enableSystemApp(a, pkg)
    }

    /** Removes every restriction and gives up Device Owner, so the app can be uninstalled normally. */
    fun teardown(ctx: Context) {
        val prefs = Prefs(ctx)
        DnsVpnService.stop(ctx)
        if (isOwner(ctx)) {
            val d = dpm(ctx)
            val a = admin(ctx)
            runCatching { d.setPackagesSuspended(a, prefs.suspendedByUs.toTypedArray(), false) }
            for (pkg in prefs.hiddenByUs) runCatching { d.setApplicationHidden(a, pkg, false) }
            for (r in prefs.restrictionsByUs) runCatching { d.clearUserRestriction(a, r) }
            runCatching { d.setCameraDisabled(a, false) }
            runCatching { d.setScreenCaptureDisabled(a, false) }
            runCatching { d.setAlwaysOnVpnPackage(a, null, false) }
            for (pkg in CHROMIUM_BROWSERS) runCatching { d.setApplicationRestrictions(a, pkg, Bundle()) }
            runCatching { d.setUninstallBlocked(a, ctx.packageName, false) }
            @Suppress("DEPRECATION")
            runCatching { d.clearDeviceOwnerApp(ctx.packageName) }
        }
        Guard.policy = Policy()
    }
}
