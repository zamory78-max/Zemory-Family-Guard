package com.familyguard.child.enforce

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.familyguard.child.Domains
import com.familyguard.child.Guard
import com.familyguard.child.sync.Events
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
        if (pkg in YOUTUBE_PKGS) checkYoutubeSettings(e)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            runCatching { ActivityWatcher.onEvent(this, pkg) { appLabel(pkg) } }
        }
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

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun onForeground(pkg: String) {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && ime.startsWith("$pkg/")) return

        // "Lock screen entirely": keep our full-screen lock on top until the parent releases it
        if (Guard.isHoldActive()) {
            LockActivity.show(this, "", Guard.holdReason())
            return
        }

        val reason = Enforcer.blockReason(this, pkg)
        if (reason != null) {
            Events.log("block", appLabel(pkg), reason)
            LockActivity.show(this, pkg, reason)
            return
        }
        if (!Guard.isPaused(this) && Enforcer.isPinLocked(this, pkg) && !Guard.isUnlocked(pkg)) {
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
            if (Guard.isPaused(this)) return
            // Lock the Google/YouTube history pages (where history can be deleted) with the owner PIN
            if (p.restrictions["lock_youtube_settings"] == true && p.pinHash.isNotEmpty() &&
                !Guard.isUnlocked(YT_SETTINGS) && isHistoryPage(host, text)
            ) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                LockActivity.show(this, YT_SETTINGS, "ytsettings")
                return
            }
            val pageBlocked = Domains.isPathBlocked(text, p.blockedPaths)
            if (!Guard.isOverridden("web:$host") && (Domains.isSiteBlocked(host, p) || pageBlocked)) {
                Events.log("web", host, if (pageBlocked) "page" else "site")
                performGlobalAction(GLOBAL_ACTION_BACK)
                LockActivity.show(this, if (pageBlocked) text else host, "web", url = text)
            }
            return
        }
    }

    private var lastYtCheck = 0L

    /** Locks YouTube's settings / history screens with the owner PIN (so watch history can't be cleared or paused). */
    private fun checkYoutubeSettings(e: AccessibilityEvent) {
        val p = Guard.policy
        if (p.restrictions["lock_youtube_settings"] != true || p.pinHash.isEmpty()) return
        if (Guard.isPaused(this) || Guard.isUnlocked(YT_SETTINGS)) return
        val cls = e.className?.toString().orEmpty()
        var hit = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !cls.startsWith("android.") && cls.contains("settings", ignoreCase = true)
        if (!hit) {
            val now = System.currentTimeMillis()
            if (now - lastYtCheck < 800) return
            lastYtCheck = now
            val root = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            } ?: return
            hit = YT_MARKERS.any { m -> root.findAccessibilityNodeInfosByText(m)?.isNotEmpty() == true }
        }
        if (hit) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            LockActivity.show(this, YT_SETTINGS, "ytsettings")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    companion object {
        /** True only while Android really has this service bound (the setting string alone proves nothing). */
        @Volatile var connected = false
        const val YT_SETTINGS = "youtube-settings"
        val YOUTUBE_PKGS = setOf(
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.google.android.youtube.tv",
        )
        /** Text that only appears on YouTube's settings / history management screens. */
        private val YT_MARKERS = listOf(
            "History & privacy", "Manage all history", "Clear watch history", "Clear all watch history",
            "Pause watch history", "Clear search history",
            "Historique et confidentialité", "Gérer tout l'historique", "Effacer l'historique des vidéos regardées",
            "السجلّ والخصوصية", "السجل والخصوصية", "إدارة كل السجلّ", "إدارة كل السجل",
            "محو سجلّ المشاهدة", "محو سجل المشاهدة", "محو كل سجلّ المشاهدة", "إيقاف سجلّ المشاهدة مؤقتًا",
            // the watch-history page itself (videos can be removed one by one there)
            "Search watch history", "Watch history", "Historique des vidéos regardées", "Rechercher dans l'historique",
            "البحث في سجلّ المشاهدة", "البحث في سجل المشاهدة", "سجلّ المشاهدة", "سجل المشاهدة",
        )

        /** Google / YouTube web pages where activity or watch history can be deleted. */
        private val HISTORY_DOMAINS = setOf("myactivity.google.com", "myaccount.google.com", "takeout.google.com")

        fun isHistoryPage(host: String, url: String): Boolean {
            if (Domains.isBlocked(host, HISTORY_DOMAINS)) return true
            val u = url.lowercase()
            return host.endsWith("youtube.com") && (u.contains("/feed/history") || u.contains("/feed/you"))
        }

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
