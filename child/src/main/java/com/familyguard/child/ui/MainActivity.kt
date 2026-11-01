package com.familyguard.child.ui

import android.content.Intent
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
import androidx.compose.material3.FilterChip
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
import com.familyguard.child.Policy
import com.familyguard.child.Prefs
import com.familyguard.child.admin.Dpm
import com.familyguard.child.sync.GuardService
import com.familyguard.child.sync.Repo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)
    private var unlocked by mutableStateOf(false)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Dpm.applyVpn(this, Guard.policy)
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
                    Text("🛡️ Zemory Family Guard", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("الإصدار ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    when {
                        !configured -> Setup { configured = true }
                        Guard.policy.pinHash.isNotEmpty() && !unlocked -> PinGate()
                        else -> Status { configured = false }
                    }
                }
            }
        }
    }

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

    @Composable
    private fun Setup(onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var name by remember { mutableStateOf(Build.MODEL ?: "") }
        val isTv = remember { packageManager.hasSystemFeature("android.software.leanback") }
        var type by remember { mutableStateOf(if (isTv) "tv" else "phone") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }

        Text("ربط هذا الجهاز بحساب الوالد", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "استخدم نفس البريد وكلمة المرور التي أنشأتها في لوحة التحكم.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("البريد الإلكتروني") }, singleLine = true,
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
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            name, { name = it }, label = { Text("اسم الجهاز (مثلاً: هاتف أحمد)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == "phone", { type = "phone" }, label = { Text("📱 هاتف") })
            FilterChip(type == "tablet", { type = "tablet" }, label = { Text("💻 تابلت") })
            FilterChip(type == "tv", { type = "tv" }, label = { Text("📺 تلفاز") })
        }
        err?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && name.isNotBlank(),
            onClick = {
                busy = true
                err = null
                scope.launch {
                    try {
                        link(email, password, name.trim(), type)
                        onDone()
                    } catch (e: Exception) {
                        err = "تعذّر الربط: ${e.localizedMessage}"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "جارٍ الربط..." else "ربط الجهاز") }
    }

    private suspend fun link(email: String, password: String, name: String, type: String) {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.signInWithEmailAndPassword(email, password).await().user?.uid
            ?: error("فشل تسجيل الدخول")
        val prefs = Prefs(this)
        val id = prefs.deviceId ?: UUID.randomUUID().toString().replace("-", "").take(20)
        val ref = Repo.device(id)
        val existing = runCatching { ref.get().await() }.getOrNull()
        val data = HashMap<String, Any>()
        data["ownerUid"] = uid
        data["name"] = name
        data["type"] = type
        data["model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        data["sdk"] = Build.VERSION.SDK_INT
        data["appVersion"] = BuildConfig.VERSION_NAME
        data["lastSeen"] = FieldValue.serverTimestamp()
        data["status"] = GuardService.statusMap(this)
        if (existing == null || !existing.exists()) {
            data["createdAt"] = FieldValue.serverTimestamp()
            data["policy"] = Policy.defaultMap()
        }
        ref.set(data, SetOptions.merge()).await()
        prefs.deviceId = id
        prefs.deviceName = name
        prefs.configured = true
        withContext(Dispatchers.Default) { Dpm.bootstrap(this@MainActivity) }
        GuardService.start(this)
    }

    @Composable
    private fun Status(onUnlinked: () -> Unit) {
        val tick = refresh // re-read status when this changes
        if (tick < 0) return
        val prefs = remember { Prefs(this) }
        val owner = Dpm.isOwner(this)
        val usage = Dpm.hasUsageAccess(this)
        val acc = Dpm.isAccessibilityOn(this)
        val ws = Dpm.hasWriteSecure(this)
        val vpnOk = VpnService.prepare(this) == null
        val paused = Guard.isPaused(this)
        var confirmUnlink by remember { mutableStateOf(false) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(prefs.deviceName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("معرّف الجهاز: ${prefs.deviceId}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (paused) Text("⏸ الحماية متوقفة مؤقتاً", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("حالة الصلاحيات", fontWeight = FontWeight.Bold)
        Check("مالك الجهاز (Device Owner)", owner, "يُفعَّل بأمر adb من الكمبيوتر — راجع README", null)
        Check("الوصول لبيانات الاستخدام", usage, "مطلوب لحساب وقت كل تطبيق") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        Check("خدمة إمكانية الوصول", acc, "مطلوبة لقفل التطبيقات برمز وتسجيل المواقع") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Check("إعادة التفعيل التلقائي (WRITE_SECURE_SETTINGS)", ws, "تمنح بأمر adb — راجع README", null)
        Check("فلتر المواقع (VPN)", vpnOk && (Guard.vpnRunning || !Guard.policy.needsVpn), "اضغط للسماح") {
            val i = VpnService.prepare(this)
            if (i != null) vpnConsent.launch(i) else {
                Dpm.applyVpn(this, Guard.policy); refresh++
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("أوامر الإعداد من الكمبيوتر (مرة واحدة):", fontWeight = FontWeight.Bold)
        Text(
            ADB_COMMANDS,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) {
                Button(onClick = { prefs.pausedUntil = 0L; GuardService.start(this@MainActivity); refresh++ }) {
                    Text("استئناف الحماية")
                }
            } else {
                OutlinedButton(onClick = {
                    prefs.pausedUntil = System.currentTimeMillis() + 30 * 60_000L
                    Thread { runCatching { com.familyguard.child.enforce.Enforcer.apply(applicationContext) } }.start()
                    refresh++
                }) { Text("إيقاف مؤقت 30 دقيقة") }
            }
            TextButton(onClick = { confirmUnlink = true }) { Text("إزالة الحماية") }
        }

        if (confirmUnlink) {
            AlertDialog(
                onDismissRequest = { confirmUnlink = false },
                title = { Text("إزالة الحماية؟") },
                text = { Text("سيتم رفع كل القيود وإلغاء وضع مالك الجهاز وفك ربط هذا الجهاز. بعدها يمكن حذف التطبيق.") },
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

    private fun unlink() {
        val prefs = Prefs(this)
        prefs.deviceId?.let { id -> runCatching { Repo.device(id).update("unlinked", true) } }
        GuardService.stop(this)
        Dpm.teardown(this)
        FirebaseAuth.getInstance().signOut()
        prefs.clear()
    }

    @Composable
    private fun Check(title: String, ok: Boolean, hint: String, onFix: (() -> Unit)?) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title)
                if (!ok) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!ok && onFix != null) TextButton(onClick = onFix) { Text("تفعيل") }
        }
    }

    companion object {
        const val ADB_COMMANDS = """adb shell dpm set-device-owner com.familyguard.child/.admin.AdminReceiver
adb shell pm grant com.familyguard.child android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.familyguard.child GET_USAGE_STATS allow"""
    }
}
