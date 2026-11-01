package com.familyguard.child.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.familyguard.child.Prefs
import com.familyguard.child.admin.Dpm
import com.familyguard.child.enforce.Enforcer

data class AppInfo(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val version: String,
    val launchable: Boolean,
    val hidden: Boolean,
    val installed: Boolean,
)

object AppInventory {
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val launchable = Enforcer.launchable(ctx)
        val hiddenByUs = Prefs(ctx).hiddenByUs
        val owner = Dpm.isOwner(ctx)
        val apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        val out = ArrayList<AppInfo>()
        for (ai in apps) {
            val pkg = ai.packageName
            if (pkg == ctx.packageName) continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val hidden = pkg in hiddenByUs ||
                (owner && runCatching { Dpm.dpm(ctx).isApplicationHidden(Dpm.admin(ctx), pkg) }.getOrDefault(false))
            val isLaunchable = pkg in launchable
            // keep user apps, launchable system apps, and anything hidden/removed we may want back
            if (system && !isLaunchable && !hidden && installed) continue
            if (!system && !installed) continue
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val version = runCatching {
                pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).versionName ?: ""
            }.getOrDefault("")
            out += AppInfo(pkg, label, system, version, isLaunchable, hidden, installed)
        }
        return out
    }
}
