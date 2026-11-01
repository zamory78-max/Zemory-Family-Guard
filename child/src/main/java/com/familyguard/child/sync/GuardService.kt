package com.familyguard.child.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.App
import com.familyguard.child.BuildConfig
import com.familyguard.child.Guard
import com.familyguard.child.Policy
import com.familyguard.child.Prefs
import com.familyguard.child.R
import com.familyguard.child.admin.Dpm
import com.familyguard.child.apps.AppInventory
import com.familyguard.child.apps.Installer
import com.familyguard.child.enforce.Enforcer
import com.familyguard.child.enforce.UsageTracker
import com.familyguard.child.ui.MainActivity
import com.familyguard.child.web.WebLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections

/** Keeps the device in sync with the parent: policy, commands, usage, apps and web history. */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var policyReg: ListenerRegistration? = null
    private var cmdReg: ListenerRegistration? = null
    private val handled = Collections.synchronizedSet(HashSet<String>())
    private var lastAppsHash = 0
    private var lastAppsUpload = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        Guard.load(this)
        Dpm.bootstrap(this)
        scope.launch { runCatching { applyAll() } }
        listen()
        loop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (policyReg == null) listen()
        return START_STICKY
    }

    override fun onDestroy() {
        policyReg?.remove()
        cmdReg?.remove()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Zemory Family Guard")
            .setContentText("الحماية مفعّلة")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun listen() {
        val id = Prefs(this).deviceId ?: return
        if (FirebaseAuth.getInstance().currentUser == null) return
        val ref = Repo.device(id)

        policyReg = ref.addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "policy", err)
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val map = snap.get("policy") as? Map<*, *> ?: return@addSnapshotListener
            val json = JSONObject(map).toString()
            Prefs(this).policyJson = json
            Guard.policy = Policy.parse(json)
            snap.getString("name")?.let { Prefs(this).deviceName = it }
            scope.launch { runCatching { applyAll() }.onFailure { Log.w(TAG, "apply", it) } }
        }

        cmdReg = ref.collection("commands").whereEqualTo("status", "pending")
            .addSnapshotListener { qs, err ->
                if (err != null) Log.w(TAG, "commands", err)
                qs?.documentChanges?.forEach { ch ->
                    if (ch.type == DocumentChange.Type.ADDED && handled.add(ch.document.id)) {
                        val doc = ch.document
                        scope.launch { handleCommand(doc) }
                    }
                }
            }
    }

    private fun applyAll() {
        Dpm.applyPolicy(this, Guard.policy)
        Enforcer.apply(this)
    }

    private suspend fun handleCommand(doc: DocumentSnapshot) {
        val type = doc.getString("type") ?: ""
        val pkg = doc.getString("pkg")
        val url = doc.getString("url")
        doc.reference.update("status", "running")
        try {
            when (type) {
                "install" -> {
                    Installer.installFromUrl(this, url ?: error("لا يوجد رابط"), doc.id)
                    return // result arrives in InstallReceiver
                }
                "uninstall" -> {
                    Installer.uninstall(this, pkg ?: error("لا يوجد تطبيق"), doc.id)
                    return
                }
                "enable_system_app" -> Dpm.enableSystemApp(this, pkg ?: error("لا يوجد تطبيق"))
                "lock_now" -> Dpm.lockNow(this)
                "reboot" -> {
                    Repo.commandResult(this, doc.id, "done", "جارٍ إعادة التشغيل")
                    delay(1500)
                    Dpm.reboot(this)
                    return
                }
                "sync" -> {
                    applyAll()
                    WebLog.flush(this)
                    uploadState(force = true)
                }
                else -> error("أمر غير معروف: $type")
            }
            Repo.commandResult(this, doc.id, "done", "تم")
        } catch (e: Throwable) {
            Repo.commandResult(this, doc.id, "error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun loop() {
        val ctx = this
        scope.launch {
            var tick = 0
            while (isActive) {
                runCatching { Enforcer.apply(ctx) }.onFailure { Log.w(TAG, "enforce", it) }
                runCatching { Dpm.ensureAccessibility(ctx) }
                if (tick % 10 == 0 && Guard.policy.needsVpn && !Guard.vpnRunning) {
                    runCatching { Dpm.applyVpn(ctx, Guard.policy) }
                }
                if (tick % 2 == 0) runCatching { WebLog.flush(ctx) }.onFailure { Log.w(TAG, "web", it) }
                if (tick % 5 == 0) runCatching { uploadState(false) }.onFailure { Log.w(TAG, "upload", it) }
                tick++
                delay(60_000L)
            }
        }
    }

    private fun uploadState(force: Boolean) {
        val id = Prefs(this).deviceId ?: return
        if (FirebaseAuth.getInstance().currentUser == null) return
        val ref = Repo.device(id)
        ref.update(
            mapOf(
                "lastSeen" to FieldValue.serverTimestamp(),
                "status" to statusMap(this),
                "appVersion" to BuildConfig.VERSION_NAME,
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "sdk" to Build.VERSION.SDK_INT,
            )
        )

        val usage = Guard.usageToday
        val apps = HashMap<String, Any>()
        for (a in AppInventory.list(this)) {
            apps[a.pkg] = mapOf(
                "l" to a.label,
                "s" to a.system,
                "v" to a.version,
                "u" to (usage[a.pkg] ?: 0),
                "h" to a.hidden,
                "i" to a.installed,
                "x" to a.launchable,
            )
        }
        val hash = apps.hashCode()
        val now = System.currentTimeMillis()
        if (force || hash != lastAppsHash || now - lastAppsUpload > 30 * 60_000L) {
            lastAppsHash = hash
            lastAppsUpload = now
            ref.collection("meta").document("apps").set(
                mapOf(
                    "apps" to apps,
                    "day" to UsageTracker.dayKey(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
        }
    }

    companion object {
        private const val TAG = "FG-Service"

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            }.onFailure { Log.w(TAG, "start", it) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }

        fun statusMap(ctx: Context): Map<String, Any> = mapOf(
            "deviceOwner" to Dpm.isOwner(ctx),
            "accessibility" to Dpm.isAccessibilityOn(ctx),
            "usageAccess" to Dpm.hasUsageAccess(ctx),
            "writeSecure" to Dpm.hasWriteSecure(ctx),
            "vpn" to Guard.vpnRunning,
            "paused" to Guard.isPaused(ctx),
        )
    }
}
