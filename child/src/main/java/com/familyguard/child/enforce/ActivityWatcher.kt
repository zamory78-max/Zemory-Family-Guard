package com.familyguard.child.enforce

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.familyguard.child.Guard
import com.familyguard.child.sync.Events

/**
 * Approximate "where did the child go inside this app" log, for the apps the owner picked
 * (e.g. Facebook). It never records what is typed in chats or password fields. It keeps only:
 *  - the headings at the top of the screen (page / group / profile names), and
 *  - text typed into a SEARCH box (found by its hint or view id), after the child stops typing.
 * Entries are uploaded as events of type "activity", at most [MAX_PER_DAY] per day.
 */
object ActivityWatcher {
    private const val MAX_PER_DAY = 150
    private const val SCAN_EVERY_MS = 2_500L
    private const val SEARCH_SETTLE_MS = 3_000L
    private const val MAX_NODES = 500

    private var lastScan = 0L
    private var lastSig = ""
    private var day = ""
    private var countToday = 0

    private var pendingSearch: String? = null
    private var pendingAt = 0L
    private var lastLoggedSearch = ""

    /** Words that appear on almost every Facebook screen and say nothing about where the child is. */
    private val NOISE = setOf(
        "facebook", "home", "search", "menu", "notifications", "watch", "marketplace", "reels", "friends", "video",
        "الرئيسية", "بحث", "القائمة", "الإشعارات", "فيديو", "الأصدقاء", "ريلز", "المتجر",
        "accueil", "rechercher", "amis", "vidéo",
    )
    private val SEARCH_HINT = listOf("search", "بحث", "ابحث", "recherche", "rechercher")

    fun onEvent(svc: AccessibilityService, pkg: String, label: () -> String) {
        val p = Guard.policy
        if (!p.activityEnabled || pkg !in p.activityApps || pkg == svc.packageName) return
        val now = System.currentTimeMillis()
        if (now - lastScan < SCAN_EVERY_MS) return
        lastScan = now

        val root = try {
            svc.rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return
        if (root.packageName?.toString() != pkg) return

        val height = svc.resources.displayMetrics.heightPixels
        val top = LinkedHashSet<String>()
        var search: String? = null
        var seen = 0

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || seen++ > MAX_NODES) return
            if (n.isVisibleToUser && !n.isPassword) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (n.isEditable) {
                    if (n.isFocused && text.length >= 2 && isSearchBox(n)) search = text.take(100)
                } else if (text.length in 2..60) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.top >= 0 && r.top < height * 0.18 && isMeaningful(text)) top += text
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)

        // typed search text: log it once the child stops typing (or leaves the search box)
        val s = search
        if (s != null) {
            if (s != pendingSearch) {
                pendingSearch = s
                pendingAt = now
            } else if (now - pendingAt >= SEARCH_SETTLE_MS && s != lastLoggedSearch) {
                lastLoggedSearch = s
                record(label(), "🔎 $s")
            }
        } else {
            val ps = pendingSearch
            if (ps != null && ps != lastLoggedSearch && ps.length >= 3) {
                lastLoggedSearch = ps
                record(label(), "🔎 $ps")
            }
            pendingSearch = null
        }

        val sig = top.take(3).joinToString(" · ")
        if (sig.isNotEmpty() && sig != lastSig) {
            lastSig = sig
            record(label(), sig)
        }
    }

    private fun isSearchBox(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.lowercase().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.lowercase().orEmpty() else ""
        val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
        return SEARCH_HINT.any { id.contains(it) || hint.contains(it) || desc.contains(it) }
    }

    private fun isMeaningful(t: String): Boolean {
        if (t.lowercase() in NOISE) return false
        if (t.all { !it.isLetter() }) return false // times, battery, counters
        return true
    }

    @Synchronized
    private fun record(app: String, detail: String) {
        val today = UsageTracker.dayKey()
        if (today != day) {
            day = today
            countToday = 0
        }
        if (countToday >= MAX_PER_DAY) return
        countToday++
        Events.log("activity", app, detail.take(160), dedupMs = 10 * 60_000L)
    }
}
