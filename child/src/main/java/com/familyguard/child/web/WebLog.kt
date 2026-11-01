package com.familyguard.child.web

import android.content.Context
import com.familyguard.child.Guard
import com.familyguard.child.Prefs
import com.familyguard.child.enforce.UsageTracker
import com.familyguard.child.sync.Repo
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects visited domains (from the DNS filter) and full URLs (from browsers), and uploads them
 * in batches to devices/{id}/web/{yyyy-MM-dd}, one document per day.
 */
object WebLog {
    private class Entry(var count: Int = 0, var last: Long = 0L, var blocked: Boolean = false)

    private val domains = ConcurrentHashMap<String, Entry>()
    private val urls = ConcurrentHashMap<String, Triple<String, String, Long>>()

    private class Media(val pkg: String, val title: String, val channel: String, val time: Long)
    private val media = ConcurrentHashMap<String, Media>()
    @Volatile private var appContext: Context? = null

    /** A video / song being played (e.g. YouTube): title and channel. */
    fun media(pkg: String, title: String, channel: String) {
        if (!Guard.policy.webLog) return
        val t = title.take(300)
        media[Integer.toHexString("$pkg|$t".hashCode())] = Media(pkg, t, channel.take(150), System.currentTimeMillis())
    }

    fun domain(name: String, blocked: Boolean) {
        if (!Guard.policy.webLog && !blocked) return
        if (name.isEmpty() || !name.contains('.') || name.endsWith(".arpa") || name.endsWith(".local")) return
        if (name.length > 200) return
        val now = System.currentTimeMillis()
        domains.compute(name) { _, old ->
            (old ?: Entry()).apply {
                count++
                last = now
                if (blocked) this.blocked = true
            }
        }
    }

    fun url(url: String, browser: String) {
        if (!Guard.policy.webLog) return
        val u = url.take(500)
        urls[Integer.toHexString(u.hashCode())] = Triple(u, browser, System.currentTimeMillis())
    }

    fun flush(ctx: Context) {
        val id = Prefs(ctx).deviceId ?: return
        appContext = ctx.applicationContext
        if (domains.isEmpty() && urls.isEmpty() && media.isEmpty()) return

        val ds = HashMap<String, Entry>()
        for (k in domains.keys.toList()) domains.remove(k)?.let { ds[k] = it }
        val us = HashMap<String, Triple<String, String, Long>>()
        for (k in urls.keys.toList()) urls.remove(k)?.let { us[k] = it }

        val dm = HashMap<String, Any>()
        for ((name, e) in ds) {
            val m = HashMap<String, Any>()
            m["c"] = FieldValue.increment(e.count.toLong())
            m["t"] = e.last
            if (e.blocked) m["b"] = true
            dm[name] = m
        }
        val um = HashMap<String, Any>()
        for ((k, v) in us) um[k] = mapOf("u" to v.first, "br" to v.second, "t" to v.third)

        val ms = HashMap<String, Media>()
        for (k in media.keys.toList()) media.remove(k)?.let { ms[k] = it }
        val mm = HashMap<String, Any>()
        val pm = ctx.packageManager
        for ((k, v) in ms) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(v.pkg, 0)).toString() }.getOrDefault(v.pkg)
            mm[k] = mapOf("p" to v.pkg, "a" to label, "ti" to v.title, "ch" to v.channel, "t" to v.time)
        }

        val day = UsageTracker.dayKey()
        val data = HashMap<String, Any>()
        data["day"] = day
        if (dm.isNotEmpty()) data["domains"] = dm
        if (um.isNotEmpty()) data["urls"] = um
        if (mm.isNotEmpty()) data["media"] = mm
        Repo.device(id).collection("web").document(day).set(data, SetOptions.merge())
    }
}
