package com.familyguard.child.enforce

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Foreground minutes per app since midnight, computed from usage events. */
object UsageTracker {
    private const val RESUMED = 1
    private const val PAUSED = 2
    private const val SCREEN_OFF = 16
    private const val SHUTDOWN = 26

    fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayKey(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    fun today(ctx: Context): Map<String, Int> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(start, now)
        } catch (e: Exception) {
            return emptyMap()
        } ?: return emptyMap()

        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        fun close(pkg: String, t: Long) {
            val s = open.remove(pkg) ?: return
            if (t > s) total[pkg] = (total[pkg] ?: 0L) + (t - s)
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                RESUMED -> {
                    for (other in open.keys.toList()) if (other != pkg) close(other, t)
                    if (!open.containsKey(pkg)) open[pkg] = t
                }
                PAUSED -> close(pkg, t)
                SCREEN_OFF, SHUTDOWN -> for (other in open.keys.toList()) close(other, t)
            }
        }
        for (pkg in open.keys.toList()) close(pkg, now)
        return total.mapValues { (it.value / 60_000L).toInt() }.filterValues { it > 0 }
    }
}
