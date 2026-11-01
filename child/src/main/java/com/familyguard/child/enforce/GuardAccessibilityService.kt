package com.familyguard.child.enforce

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.familyguard.child.Domains
import com.familyguard.child.Guard
import com.familyguard.child.ui.LockActivity
import com.familyguard.child.web.WebLog

/**
 * Second enforcement layer: sees which app comes to the foreground (PIN-locked apps, blocked apps
 * when not Device Owner) and reads the address bar of common browsers to log and block sites.
 */
class GuardAccessibilityService : AccessibilityService() {

    private var lastUrlCheck = 0L
    private var lastUrl = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onForeground(pkg)
                if (BROWSERS.containsKey(pkg)) checkUrl(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (BROWSERS.containsKey(pkg)) {
                    val now = System.currentTimeMillis()
                    if (now - lastUrlCheck > 1500) {
                        lastUrlCheck = now
                        checkUrl(pkg)
                    }
                }
            }
        }
    }

    private fun onForeground(pkg: String) {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && ime.startsWith("$pkg/")) return

        val reason = Enforcer.blockReason(this, pkg)
        if (reason != null) {
            LockActivity.show(this, pkg, reason)
            return
        }
        if (!Guard.isPaused(this) && Enforcer.isPinLocked(pkg) && !Guard.isUnlocked(pkg)) {
            LockActivity.show(this, pkg, "pin")
        }
    }

    private fun checkUrl(pkg: String) {
        val ids = BROWSERS[pkg] ?: return
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull() ?: continue
            if (node.isFocused) return // the child is typing
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text == lastUrl) return
            val host = Domains.hostOf(text) ?: return
            lastUrl = text
            WebLog.url(text, pkg)
            val p = Guard.policy
            if (p.webFilter && Domains.isBlocked(host, p.blockedDomains)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                LockActivity.show(this, host, "web")
            }
            return
        }
    }

    override fun onInterrupt() {}

    companion object {
        /** Browser package -> address bar view ids. */
        val BROWSERS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.kiwibrowser.browser" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.opera.browser" to listOf("url_field"),
            "com.opera.mini.native" to listOf("url_field"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
            "com.mi.globalbrowser" to listOf("url"),
        )
    }
}
