package com.familyguard.child.sync

import android.content.Context
import com.familyguard.child.Prefs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Timeline of noteworthy things that happened on the child device (blocks, PIN unlocks, bedtime,
 * safe-mode boots, permission changes). Uploaded to devices/{id}/events for the reports tab.
 */
object Events {
    private data class Ev(val type: String, val label: String, val detail: String, val time: Long)

    private val queue = ConcurrentLinkedQueue<Ev>()
    private val lastByKey = HashMap<String, Long>()

    /** Records an event, de-duplicated so the same thing isn't logged many times per minute. */
    @Synchronized
    fun log(type: String, label: String, detail: String = "", dedupMs: Long = 60_000L) {
        val key = "$type|$label|$detail"
        val now = System.currentTimeMillis()
        if ((lastByKey[key] ?: 0L) > now - dedupMs) return
        lastByKey[key] = now
        if (lastByKey.size > 500) lastByKey.clear()
        queue.add(Ev(type, label, detail, now))
        while (queue.size > 300) queue.poll()
    }

    fun flush(ctx: Context) {
        val id = Prefs(ctx).deviceId ?: return
        if (FirebaseAuth.getInstance().currentUser == null || queue.isEmpty()) return
        val col = Repo.device(id).collection("events")
        var n = 0
        while (n < 60) {
            val e = queue.poll() ?: break
            n++
            col.add(
                mapOf(
                    "type" to e.type,
                    "label" to e.label,
                    "detail" to e.detail,
                    "t" to e.time,
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            )
        }
    }
}
