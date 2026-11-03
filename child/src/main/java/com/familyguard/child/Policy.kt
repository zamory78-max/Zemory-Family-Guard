package com.familyguard.child

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Rules set by the parent. Stored in Firestore at devices/{id}.policy and cached locally as JSON,
 * so they keep working offline.
 */
data class Policy(
    val blocked: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val pinLocked: Set<String> = emptySet(),
    val limits: Map<String, Int> = emptyMap(),
    val pinHash: String = "",
    val restrictions: Map<String, Boolean> = emptyMap(),
    val webFilter: Boolean = false,
    val webLog: Boolean = true,
    val safeSearch: Boolean = false,
    val blockYoutube: Boolean = false,
    val blockAds: Boolean = false,
    val blockedDomains: Set<String> = emptySet(),
    val allowOnly: Boolean = false,
    val allowedDomains: Set<String> = emptySet(),
    val bedtimeEnabled: Boolean = false,
    val bedStart: String = "21:00",
    val bedEnd: String = "07:00",
    val bedAllowed: Set<String> = emptySet(),
    val pauseAll: Boolean = false,
    val lockNow: Boolean = false,
    val overrideMinutes: Int = 30,
    val unlockMinutes: Int = 10,
    val maxAttempts: Int = 5,
    val lockoutMinutes: Int = 5,
    val blockedPaths: Set<String> = emptySet(),
    val screenEnabled: Boolean = false,
    /** What happens when the screen window is over: "allowed" = only the allowed apps keep working, "lock" = lock the whole screen. */
    val screenMode: String = "allowed",
    val screenAllowed: Set<String> = emptySet(),
    /** Per weekday (0 = Sunday ... 6 = Saturday): the time window in which the screen is open. */
    val screenDays: Map<Int, ScreenDay> = emptyMap(),
) {
    /** One weekday of the screen schedule. [on] false = no limit on that day. Minutes since midnight. */
    data class ScreenDay(val on: Boolean, val start: Int, val end: Int)

    val needsVpn: Boolean get() = webFilter || webLog || safeSearch || blockYoutube || blockAds

    fun isBedtimeNow(): Boolean {
        if (!bedtimeEnabled) return false
        val s = minutes(bedStart) ?: return false
        val e = minutes(bedEnd) ?: return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s == e) false else if (s < e) now in s until e else (now >= s || now < e)
    }

    private fun today(): ScreenDay? {
        val c = Calendar.getInstance()
        return screenDays[c.get(Calendar.DAY_OF_WEEK) - 1] // Calendar: SUNDAY = 1
    }

    /** True when today's screen window is over (or has not started yet). */
    fun isScreenClosedNow(): Boolean {
        if (!screenEnabled) return false
        val d = today() ?: return false
        if (!d.on || d.start >= d.end) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return now < d.start || now >= d.end
    }

    /** The whole screen must be locked now. */
    fun screenLocksNow() = screenMode == "lock" && isScreenClosedNow()

    /** Only the allowed apps may run now. */
    fun screenRestrictsApps() = screenMode != "lock" && isScreenClosedNow()

    /** When the screen opens again, as text for the lock screen (e.g. "07:00"), or null. */
    fun screenOpensText(): String? {
        val d = today() ?: return null
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        if (now < d.start) return fmt(d.start)
        val next = screenDays[(c.get(Calendar.DAY_OF_WEEK) - 1 + 1) % 7]
        return if (next != null && next.on && next.start < next.end) "غداً ${fmt(next.start)}" else "غداً"
    }

    companion object {
        fun parse(json: String?): Policy {
            if (json.isNullOrBlank()) return Policy()
            return try {
                fromJson(JSONObject(json))
            } catch (e: Exception) {
                Policy()
            }
        }

        fun fromJson(o: JSONObject): Policy {
            val web = o.optJSONObject("web") ?: JSONObject()
            val bed = o.optJSONObject("bedtime") ?: JSONObject()
            val screen = o.optJSONObject("screen") ?: JSONObject()
            return Policy(
                blocked = o.optJSONArray("blocked").strings(),
                hidden = o.optJSONArray("hidden").strings(),
                pinLocked = o.optJSONArray("pinLocked").strings(),
                limits = o.optJSONObject("limits").intMap(),
                pinHash = o.optString("pinHash", ""),
                restrictions = o.optJSONObject("restrictions").boolMap(),
                webFilter = web.optBoolean("filter", false),
                webLog = web.optBoolean("log", true),
                safeSearch = web.optBoolean("safeSearch", false),
                blockYoutube = web.optBoolean("blockYoutube", false),
                blockAds = web.optBoolean("blockAds", false),
                blockedDomains = web.optJSONArray("blocked").strings()
                    .mapNotNull { Domains.normalize(it) }.toSet(),
                allowOnly = web.optBoolean("allowOnly", false),
                allowedDomains = web.optJSONArray("allowed").strings()
                    .mapNotNull { Domains.normalize(it) }.toSet(),
                bedtimeEnabled = bed.optBoolean("enabled", false),
                bedStart = bed.optString("start", "21:00"),
                bedEnd = bed.optString("end", "07:00"),
                bedAllowed = bed.optJSONArray("allowed").strings(),
                pauseAll = o.optBoolean("pauseAll", false),
                lockNow = o.optBoolean("lockNow", false),
                overrideMinutes = o.optInt("overrideMinutes", 30).coerceIn(1, 1440),
                unlockMinutes = o.optInt("unlockMinutes", 10).coerceIn(1, 1440),
                maxAttempts = o.optInt("maxAttempts", 5).coerceIn(0, 50),
                lockoutMinutes = o.optInt("lockoutMinutes", 5).coerceIn(1, 1440),
                blockedPaths = web.optJSONArray("blockedPaths").strings(),
                screenEnabled = screen.optBoolean("enabled", false),
                screenMode = if (screen.optString("mode", "allowed") == "lock") "lock" else "allowed",
                screenAllowed = screen.optJSONArray("allowed").strings(),
                screenDays = parseScreenDays(screen.optJSONObject("days")),
            )
        }

        private fun parseScreenDays(o: JSONObject?): Map<Int, ScreenDay> {
            if (o == null) return emptyMap()
            val out = HashMap<Int, ScreenDay>()
            for (i in 0..6) {
                val d = o.optJSONObject(i.toString()) ?: continue
                val s = minutes(d.optString("start", "")) ?: continue
                val e = minutes(d.optString("end", "")) ?: continue
                out[i] = ScreenDay(d.optBoolean("on", true), s, e)
            }
            return out
        }

        /** Defaults written when a device is first linked. */
        fun defaultMap(): Map<String, Any> = mapOf(
            "blocked" to emptyList<String>(),
            "hidden" to emptyList<String>(),
            "pinLocked" to emptyList<String>(),
            "limits" to emptyMap<String, Any>(),
            "pinHash" to "",
            "restrictions" to mapOf(
                "no_factory_reset" to true,
                "no_add_user" to true,
                "no_safe_boot" to true,
                "no_install_unknown_sources" to true,
                "no_config_date_time" to true,
                "no_control_apps" to true,
                "lock_settings" to true,
                "lock_youtube_settings" to true,
            ),
            "web" to mapOf(
                "filter" to false,
                "log" to true,
                "safeSearch" to false,
                "blockYoutube" to false,
                "blockAds" to false,
                "blocked" to emptyList<String>(),
                "allowOnly" to false,
                "allowed" to emptyList<String>(),
                "blockedPaths" to emptyList<String>(),
            ),
            "bedtime" to mapOf(
                "enabled" to false,
                "start" to "21:00",
                "end" to "07:00",
                "allowed" to emptyList<String>(),
            ),
            "screen" to mapOf(
                "enabled" to false,
                "mode" to "allowed",
                "allowed" to emptyList<String>(),
                "days" to (0..6).associate { it.toString() to mapOf("on" to true, "start" to "07:00", "end" to "21:00") },
            ),
            "pauseAll" to false,
            "lockNow" to false,
            "overrideMinutes" to 30,
            "unlockMinutes" to 10,
            "maxAttempts" to 5,
            "lockoutMinutes" to 5,
        )

        private fun minutes(hhmm: String): Int? {
            val p = hhmm.trim().split(":")
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            return h * 60 + m
        }
    }
}

private fun JSONArray?.strings(): Set<String> {
    if (this == null) return emptySet()
    val out = HashSet<String>()
    for (i in 0 until length()) {
        val s = optString(i, "").trim()
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}

private fun JSONObject?.intMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    val out = HashMap<String, Int>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        val v = optInt(k, 0)
        if (v > 0) out[k] = v
    }
    return out
}

private fun JSONObject?.boolMap(): Map<String, Boolean> {
    if (this == null) return emptyMap()
    val out = HashMap<String, Boolean>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = optBoolean(k, false)
    }
    return out
}

object Domains {
    private val YOUTUBE = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
        "youtubei.googleapis.com", "youtube.googleapis.com", "yt3.ggpht.com",
    )

    /** YouTube everywhere, including videos embedded in other websites. */
    fun isYoutube(name: String) = isBlocked(name, YOUTUBE)

    private val ADS = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com", "googletagservices.com", "googletagmanager.com", "google-analytics.com", "admob.com", "adsense.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "propellerads.com", "adcolony.com", "unityads.unity3d.com", "applovin.com", "ads.mopub.com", "moatads.com", "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net", "amazon-adsystem.com", "ads.tiktok.com", "ads.youtube.com", "static.ads-twitter.com")

    /** Ad / tracker networks (Google Ads, AdMob, etc.). */
    fun isAd(name: String) = isBlocked(name, ADS)

    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore(':')
        s = s.trimEnd('.').removePrefix("*.")
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return if (s.contains('.') && !s.contains(' ')) s else null
    }

    /** Host from a browser address bar text, or null if it does not look like a URL. */
    fun hostOf(text: String): String? = normalize(text)

    /** A full URL reduced to "host/path?query" without scheme or "www.", for page-level blocking. */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.substringAfter("://")
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return s.trimEnd('/')
    }

    /** True when the visited URL falls under one of the blocked page prefixes. */
    fun isPathBlocked(url: String, paths: Set<String>): Boolean {
        if (paths.isEmpty()) return false
        val u = normalizeUrl(url)
        return paths.any { p ->
            val n = normalizeUrl(p)
            n.isNotEmpty() && (u == n || u.startsWith("$n/") || u.startsWith("$n?") || u.startsWith("$n&"))
        }
    }

    /** Is this site blocked by the block list, a blocked page, or (in allow-only mode) missing from the allow list? */
    fun isSiteBlocked(host: String, p: Policy): Boolean =
        (p.webFilter && isBlocked(host, p.blockedDomains)) ||
            (p.allowOnly && !isBlocked(host, p.allowedDomains))

    fun isBlocked(name: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        var d = name.lowercase().trimEnd('.')
        while (true) {
            if (d in blocked) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }
}
