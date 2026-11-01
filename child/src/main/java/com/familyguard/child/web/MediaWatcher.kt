package com.familyguard.child.web

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.familyguard.child.Guard
import com.familyguard.child.admin.Dpm

/** Needed only so Android lets us read the media sessions (title of the video being played). */
class MediaListener : NotificationListenerService()

/**
 * Records what is being played in YouTube (and other media apps): title + channel,
 * read from the app's media session. Works on phones, tablets and Android TV.
 */
object MediaWatcher {
    private const val TAG = "FG-Media"
    private var started = false
    private val callbacks = HashMap<Any, MediaController.Callback>()
    private val recent = HashMap<String, Long>()

    fun isEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** In full protection the app re-enables the listener itself (WRITE_SECURE_SETTINGS). */
    fun ensureEnabled(ctx: Context) {
        if (isEnabled(ctx) || !Dpm.hasWriteSecure(ctx)) return
        runCatching {
            val me = ComponentName(ctx, MediaListener::class.java).flattenToString()
            val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", if (cur.isBlank()) me else "$cur:$me")
        }
    }

    fun start(ctx: Context) {
        if (started || !isEnabled(ctx)) return
        val app = ctx.applicationContext
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(app, MediaListener::class.java)
            val main = Handler(Looper.getMainLooper())
            main.post {
                try {
                    msm.addOnActiveSessionsChangedListener({ list -> attach(list) }, comp, main)
                    attach(msm.getActiveSessions(comp))
                } catch (e: Exception) {
                    Log.w(TAG, "sessions", e)
                    started = false
                }
            }
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
        }
    }

    private fun attach(list: List<MediaController>?) {
        if (list == null) return
        for (c in list) {
            val token = c.sessionToken
            if (callbacks.containsKey(token)) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = record(c.packageName, metadata, c.playbackState)
                override fun onPlaybackStateChanged(state: PlaybackState?) = record(c.packageName, c.metadata, state)
            }
            try {
                c.registerCallback(cb, Handler(Looper.getMainLooper()))
                callbacks[token] = cb
            } catch (e: Exception) {
                Log.w(TAG, "register", e)
            }
            record(c.packageName, c.metadata, c.playbackState)
        }
    }

    private fun record(pkg: String, m: MediaMetadata?, state: PlaybackState?) {
        if (state?.state != PlaybackState.STATE_PLAYING || m == null) return
        if (!Guard.policy.webLog) return
        val title = (m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.trim().orEmpty()
        if (title.isEmpty()) return
        val channel = (m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.trim().orEmpty()
        val key = "$pkg|$title"
        val now = System.currentTimeMillis()
        if ((recent[key] ?: 0L) > now - 30 * 60_000L) return
        if (recent.size > 200) recent.clear()
        recent[key] = now
        WebLog.media(pkg, title, channel)
    }
}
