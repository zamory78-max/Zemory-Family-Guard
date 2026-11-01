package com.familyguard.child.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.child.BuildConfig
import com.familyguard.child.Guard
import com.familyguard.child.Prefs
import com.familyguard.child.admin.Dpm
import com.familyguard.child.sync.GuardService
import com.familyguard.child.sync.Repo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)
    private var unlocked by mutableStateOf(false)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Dpm.applyVpn(this, Guard.policy)
        refresh++
    }
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs(this).configured) GuardService.start(this)
        setContent { GuardTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onStop() {
        super.onStop()
        unlocked = false // ask for the PIN again next time
    }

    @Composable
    private fun Root() {
        val prefs = remember { Prefs(this) }
        var configured by remember { mutableStateOf(prefs.configured) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    Text("🛡️ Zemory Family Guard", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("الإصدار ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    when {
                        !configured -> Pairing { configured = true }
                        Guard.policy.pinHash.isNotEmpty() && !unlocked -> PinGate()
                        else -> Wizard { configured = false }
                    }
                }
            }
        }
    }

    // ---------------- PIN ----------------

    @Composable
    private fun PinGate() {
        var err by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("هذه الشاشة للوالد فقط", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            PinPad(err) { pin ->
                if (Guard.checkPin(pin)) unlocked = true else err = "رمز غير صحيح"
            }
        }
    }

    // ---------------- Linking (child account) ----------------

    @Composable
    private fun Pairing(onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }

        Text("ربط هذا الجهاز", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "اكتب بريد وكلمة مرور هذا الجهاز، كما أنشأهما الوالد في تطبيقه من «إضافة جهاز».",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("البريد الإلكتروني للجهاز") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        err?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && email.contains('@') && password.length >= 6,
            onClick = {
                busy = true
                err = null
                scope.launch {
                    try {
                        link(email, password)
                        onDone()
                    } catch (e: Exception) {
                        err = friendlyError(e)
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "جارٍ الربط..." else "ربط الجهاز", fontSize = 17.sp) }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            e is LinkException -> e.message ?: "تعذّر الربط"
            e is FirebaseAuthInvalidCredentialsException || e is FirebaseAuthInvalidUserException ->
                "البريد أو كلمة المرور غير صحيحة."
            e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "لا توجد صلاحية. تأكد أن قواعد Firestore محدّثة."
            msg.contains("network", ignoreCase = true) || msg.contains("UNAVAILABLE") ->
                "لا يوجد اتصال بالإنترنت. تأكد من الشبكة وأعد المحاولة."
            else -> "تعذّر الربط: $msg"
        }
    }

    private class LinkException(message: String) : Exception(message)

    private suspend fun link(email: String, password: String) {
        val auth = FirebaseAuth.getInstance()
        auth.signOut()
        val uid = auth.signInWithEmailAndPassword(email, password).await().user?.uid
            ?: throw LinkException("فشل تسجيل الدخول")

        val found = Repo.db.collection("devices").whereEqualTo("deviceUid", uid).limit(1).get().await()
        val doc = found.documents.firstOrNull()
        if (doc == null) {
            auth.signOut()
            throw LinkException("هذا الحساب غير مرتبط بأي جهاز. أضف الجهاز أولاً من تطبيق الوالد.")
        }
        Repo.device(doc.id).update(
            mapOf(
                "unlinked" to false,
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "sdk" to Build.VERSION.SDK_INT,
                "appVersion" to BuildConfig.VERSION_NAME,
                "lastSeen" to FieldValue.serverTimestamp(),
                "status" to GuardService.statusMap(this),
            )
        ).await()

        val prefs = Prefs(this)
        prefs.deviceId = doc.id
        prefs.deviceName = doc.getString("name") ?: email
        prefs.configured = true
        withContext(Dispatchers.Default) { Dpm.bootstrap(this@MainActivity) }
        GuardService.start(this)
    }

    // ---------------- Setup wizard / status ----------------

    private class Step(
        val title: String,
        val hint: String,
        val done: Boolean,
        val action: String?,
        val extra: (@Composable () -> Unit)?,
        val onAction: (() -> Unit)?,
    )

    private fun openSafely(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) {
            }
        }
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun autostartIntents(): Array<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        val list = ArrayList<Intent>()
        fun comp(p: String, c: String) = Intent().setComponent(ComponentName(p, c))
        when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                list += comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> {
                list += comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                list += comp("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
            }
            m.contains("vivo") ->
                list += comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            m.contains("huawei") || m.contains("honor") ->
                list += comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        }
        list += appDetails()
        return list.toTypedArray()
    }

    private fun needsAutostartStep(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "huawei", "honor", "infinix", "tecno", "itel")
            .any { m.contains(it) }
    }

    private fun buildSteps(prefs: Prefs): List<Step> {
        val owner = Dpm.isOwner(this)
        val steps = ArrayList<Step>()

        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            steps += Step("الإشعارات", "لإظهار أن الحماية تعمل", ok, "سماح", null) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        steps += Step(
            "الوصول لبيانات الاستخدام",
            "اختر Zemory Family Guard من القائمة وفعّل «السماح».",
            Dpm.hasUsageAccess(this), "فتح", null,
        ) {
            openSafely(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
        }

        val accOn = Dpm.isAccessibilityOn(this)
        val accExtra: (@Composable () -> Unit)? =
            if (!accOn && !owner && Build.VERSION.SDK_INT >= 33) {
                {
                    Text(
                        "إذا كان الخيار رمادياً أو ظهرت رسالة «إعداد مقيّد»: اضغط «معلومات التطبيق»، ثم ⋮ في الأعلى، ثم «السماح بالإعدادات المقيّدة»، ثم ارجع هنا.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { openSafely(appDetails()) }) { Text("معلومات التطبيق") }
                }
            } else null
        steps += Step(
            "خدمة إمكانية الوصول",
            "افتح «التطبيقات المثبّتة» أو «الخدمات المنزّلة»، ثم Zemory Family Guard، وفعّلها.",
            accOn, "فتح", accExtra,
        ) { openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        if (!owner) {
            steps += Step(
                "حماية من الحذف",
                "تفعيل «مسؤول الجهاز» يمنع حذف التطبيق قبل إلغاء الحماية.",
                Dpm.isAdminActive(this), "تفعيل", null,
            ) {
                openSafely(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Dpm.admin(this))
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "يمنع حذف Zemory Family Guard من جهاز الطفل دون علم الوالد.",
                        )
                )
            }
        }

        if (Guard.policy.needsVpn) {
            val vpnOk = VpnService.prepare(this) == null
            steps += Step(
                "فلتر المواقع",
                "اضغط «موافق» في رسالة طلب الاتصال (VPN محلي لتسجيل وحظر المواقع).",
                vpnOk && Guard.vpnRunning, "سماح", null,
            ) {
                val i = VpnService.prepare(this)
                if (i != null) vpnConsent.launch(i) else {
                    Dpm.applyVpn(this, Guard.policy); refresh++
                }
            }
        }

        steps += Step(
            "العمل في الخلفية",
            "اختر «السماح» حتى لا يوقف النظام الحماية لتوفير البطارية.",
            Dpm.isIgnoringBattery(this), "سماح", null,
        ) {
            openSafely(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }

        if (!owner && needsAutostartStep()) {
            val autoExtra: (@Composable () -> Unit)? = if (!prefs.autostartDone) {
                { TextButton(onClick = { prefs.autostartDone = true; refresh++ }) { Text("تم ✓") } }
            } else null
            steps += Step(
                "التشغيل التلقائي",
                "فعّل «التشغيل التلقائي» لـ Zemory Family Guard، ثم ارجع واضغط «تم».",
                prefs.autostartDone, "فتح", autoExtra,
            ) { openSafely(*autostartIntents()) }
        }

        steps += Step(
            "رمز الوالد",
            "عيّنه من تطبيق الوالد ← الجهاز ← القيود والإعدادات. بعدها يُقفل هذا التطبيق والإعدادات برمزك.",
            Guard.policy.pinHash.isNotEmpty(), null, null, null,
        )
        return steps
    }

    @Composable
    private fun Wizard(onUnlinked: () -> Unit) {
        val tick = refresh // re-read status when this changes
        if (tick < 0) return
        val prefs = remember { Prefs(this) }
        val owner = Dpm.isOwner(this)
        val steps = buildSteps(prefs)
        val done = steps.count { it.done }
        val paused = Guard.isPaused(this)
        var confirmUnlink by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(prefs.deviceName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (owner) "حماية كاملة (مالك الجهاز)" else "حماية عادية",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                )
                if (paused) Text("⏸ الحماية متوقفة مؤقتاً", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (done == steps.size) {
            Text("✅ الحماية مكتملة", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("يمكنك إغلاق هذه الشاشة. سيظهر الجهاز في تطبيق الوالد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("خطوات الإعداد ($done من ${steps.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { done.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))

        val firstPending = steps.indexOfFirst { !it.done }
        steps.forEachIndexed { i, s -> StepRow(i + 1, s, highlight = i == firstPending) }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) {
                Button(onClick = { prefs.pausedUntil = 0L; GuardService.start(this@MainActivity); refresh++ }) {
                    Text("استئناف الحماية")
                }
            } else if (Guard.policy.pinHash.isNotEmpty()) {
                OutlinedButton(onClick = {
                    prefs.pausedUntil = System.currentTimeMillis() + 30 * 60_000L
                    Thread { runCatching { com.familyguard.child.enforce.Enforcer.apply(applicationContext) } }.start()
                    refresh++
                }) { Text("إيقاف مؤقت 30 دقيقة") }
            }
            TextButton(onClick = { confirmUnlink = true }) { Text("إزالة الحماية") }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "إخفاء الإعداد المتقدم" else "إعداد متقدم: حماية كاملة عبر الكمبيوتر")
        }
        if (showAdvanced) {
            Text(
                "على جهاز بدون حسابات، مع تفعيل تصحيح USB، شغّل من الكمبيوتر:",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(ADB_COMMANDS, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        }

        if (confirmUnlink) {
            AlertDialog(
                onDismissRequest = { confirmUnlink = false },
                title = { Text("إزالة الحماية؟") },
                text = { Text("سيتم رفع كل القيود وفك ربط هذا الجهاز وإبلاغ الوالد. بعدها يمكن حذف التطبيق.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnlink = false
                        unlink()
                        onUnlinked()
                    }) { Text("إزالة") }
                },
                dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("إلغاء") } },
            )
        }
    }

    @Composable
    private fun StepRow(n: Int, s: Step, highlight: Boolean) {
        val colors = if (highlight) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors()
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = colors) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.done) "✅" else "$n", fontSize = 18.sp, modifier = Modifier.widthIn(min = 28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold)
                    if (!s.done) {
                        Text(s.hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.extra?.invoke()
                    }
                }
                if (!s.done && s.action != null && s.onAction != null) {
                    if (highlight) Button(onClick = s.onAction) { Text(s.action) }
                    else FilledTonalButton(onClick = s.onAction) { Text(s.action) }
                }
            }
        }
    }

    private fun unlink() {
        val prefs = Prefs(this)
        prefs.deviceId?.let { id -> runCatching { Repo.device(id).update("unlinked", true) } }
        GuardService.stop(this)
        Dpm.teardown(this)
        runCatching { Dpm.dpm(this).removeActiveAdmin(Dpm.admin(this)) }
        FirebaseAuth.getInstance().signOut()
        prefs.clear()
    }

    companion object {
        const val ADB_COMMANDS = """adb shell dpm set-device-owner com.familyguard.child/.admin.AdminReceiver
adb shell pm grant com.familyguard.child android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.familyguard.child GET_USAGE_STATS allow"""
    }
}
