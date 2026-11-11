package com.familyguard.child.enforce

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
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
    private var lastFgCheck = 0L
    private var lastUrl = ""
    private var lastClickLabel = ""
    private var lastClickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        checkAllWindows() // TYPE_WINDOWS_CHANGED events carry no package, so look before the package check
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) rememberClick(e)
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = e.className?.toString().orEmpty()
            if (cls.contains("ActionDisabledByAdmin", true) || cls.contains("SuspendedApp", true) ||
                cls.contains("BlockedByAdmin", true) || cls.contains("DeviceAdminSupport", true)
            ) handleSuspendedDialog()
        }
        if (pkg in YOUTUBE_PKGS) checkYoutubeSettings(e)
        if (pkg == "com.android.systemui" || pkg == "android") checkPowerMenu()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            runCatching { ActivityWatcher.onEvent(this, pkg) { appLabel(pkg) } }
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onForeground(pkg)
                if (BROWSERS.containsKey(pkg)) checkUrl(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // An app that was already open when it got blocked (or kept open behind the lock screen) sends no
                // window-change event, so look again every ~2 s while it is the active window.
                val t = System.currentTimeMillis()
                if (t - lastFgCheck > 2_000L) {
                    lastFgCheck = t
                    val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                    if (active == pkg) onForeground(pkg)
                }
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

    /** Remember what icon was tapped (label or description) so a suspended app can be identified later. */
    private fun rememberClick(e: AccessibilityEvent) {
        val t = (e.text?.joinToString(" ").orEmpty() + " " + e.contentDescription.orEmpty()).trim()
        if (t.isEmpty()) return
        lastClickLabel = t.lowercase()
        lastClickAt = System.currentTimeMillis()
    }

    /**
     * With Device Owner, blocked apps are suspended by Android, which shows its own "action not allowed" message.
     * Replace it with our request window so the child can ask for permission, as in normal protection.
     */
    private fun handleSuspendedDialog() {
        if (Guard.isPaused(this)) return
        val suspended = com.familyguard.child.Prefs(this).suspendedByUs
        if (suspended.isEmpty()) return
        val texts = StringBuilder()
        runCatching {
            val root = rootInActiveWindow
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 12) return
                n.text?.let { texts.append(it).append(' ') }
                n.contentDescription?.let { texts.append(it).append(' ') }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
        }
        val dialogText = texts.toString().lowercase()
        val recentClick = if (System.currentTimeMillis() - lastClickAt < 4_000L) lastClickLabel else ""
        var target: String? = null
        for (pkg in suspended) {
            val label = appLabel(pkg).lowercase()
            if (label.length < 2) continue
            if (dialogText.contains(label) || (recentClick.isNotEmpty() && recentClick.contains(label))) {
                target = pkg
                break
            }
        }
        val pkg = target ?: return
        val reason = Enforcer.blockReason(this, pkg) ?: "blocked"
        Events.log("block", appLabel(pkg), reason)
        performGlobalAction(GLOBAL_ACTION_BACK)
        LockActivity.show(this, pkg, reason)
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

    private var lastWinCheck = 0L

    /**
     * Split-screen / pop-up / freeform: a blocked app can stay visible next to our lock screen. Look at every
     * application window, and if one of them is blocked, leave multi-window mode (Home) and show the lock.
     */
    private fun checkAllWindows() {
        val now = System.currentTimeMillis()
        if (now - lastWinCheck < 700) return
        lastWinCheck = now
        val wins = try { windows } catch (_: Exception) { null } ?: return
        val apps = wins.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.size < 2) return
        for (w in apps) {
            val p = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            if (p == packageName || p == "com.android.systemui" || p == "android") continue
            val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (ime != null && ime.startsWith("$p/")) continue
            val reason = Enforcer.blockReason(this, p)
            val pin = !Guard.isPaused(this) && Enforcer.isPinLocked(this, p) && !Guard.isUnlocked(p)
            if (reason != null || pin || Guard.isHoldActive()) {
                Events.log("block", appLabel(p), reason ?: "pin")
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockActivity.show(this, p, reason ?: "pin")
                return
            }
        }
    }

    private var lastPowerCheck = 0L

    /**
     * "Lock the power menu": closes the power-off / restart dialog (it is the way to Safe Mode) as soon as it opens.
     * Recognised by showing a "power off" and a "restart" entry together, so the quick panel is left alone.
     */
    private fun checkPowerMenu() {
        if (Guard.policy.restrictions["lock_power_menu"] != true) return
        if (Guard.isPaused(this)) return
        val now = System.currentTimeMillis()
        if (now - lastPowerCheck < 120) return
        lastPowerCheck = now
        // The power menu is a separate system window and is not always the "active" one, so look at all of them.
        val roots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow }.getOrNull()?.let { roots.add(it) }
        runCatching { windows }.getOrNull()?.forEach { w -> runCatching { w.root }.getOrNull()?.let { roots.add(it) } }
        val hit = roots.any { root ->
            POWER_OFF_LABELS.any { root.findAccessibilityNodeInfosByText(it)?.isNotEmpty() == true } &&
                POWER_RESTART_LABELS.any { root.findAccessibilityNodeInfosByText(it)?.isNotEmpty() == true }
        }
        if (!hit) return
        // Close it, and do it again right away in case the first attempt only dismissed an animation.
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 150)
        Events.log("block", "قائمة الطاقة", "power")
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
        private val POWER_OFF_LABELS = listOf("Power off", "إيقاف التشغيل", "ايقاف التشغيل", "إيقاف", "Éteindre", "Apagar")
        private val POWER_RESTART_LABELS = listOf(
            "Restart", "إعادة تشغيل", "إعادة التشغيل", "اعادة تشغيل", "Redémarrer", "Reiniciar",
            "Emergency call", "مكالمة طوارئ", "Medical info", "معلومات طبية",
        )
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
