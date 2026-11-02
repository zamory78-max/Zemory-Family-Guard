package com.familyguard.child.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.child.Domains
import com.familyguard.child.Guard
import com.familyguard.child.Prefs
import com.familyguard.child.enforce.Enforcer
import com.familyguard.child.sync.Events
import com.familyguard.child.sync.Repo
import com.google.firebase.firestore.FieldValue
import kotlin.concurrent.thread

/**
 * Full screen shown over a blocked app, a PIN-locked app or a blocked website.
 * For websites the child can send a request to the owner to allow the site.
 */
class LockActivity : ComponentActivity() {

    private var target by mutableStateOf("")
    private var reason by mutableStateOf("blocked")
    private var error by mutableStateOf<String?>(null)
    private var requestUrl by mutableStateOf("")
    private var requestState by mutableStateOf("") // "", "sending", "sent", "error"
    private var showPin by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (reason != "hold") goHome() // the hold lock cannot be dismissed with Back
            }
        })
        setContent { GuardTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent?) {
        target = i?.getStringExtra("t") ?: ""
        reason = i?.getStringExtra("r") ?: "blocked"
        requestUrl = i?.getStringExtra("u") ?: target
        requestState = ""
        showPin = false
        error = null
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private val isWeb get() = reason == "web" || reason == "request"

    private fun submit(pin: String) {
        if (Guard.policy.pinHash.isEmpty()) {
            error = "لم يتم تعيين رمز المالك من لوحة التحكم"
            return
        }
        if (!Guard.checkPin(pin)) {
            error = "رمز غير صحيح"
            return
        }
        val now = System.currentTimeMillis()
        val unlockMs = Guard.policy.unlockMinutes * 60_000L
        val overrideMs = Guard.policy.overrideMinutes * 60_000L
        Events.log("unlock", if (isWeb) target else appLabel(target), reason)
        when (reason) {
            "pin", "ytsettings" -> Guard.unlockedUntil[target] = now + unlockMs
            "hold" -> Guard.lockHoldSuppressedUntil = now + overrideMs
            "web" -> Guard.overrideUntil["web:$target"] = now + overrideMs
            else -> {
                Guard.overrideUntil[target] = now + overrideMs
                val ctx = applicationContext
                val pkg = target
                thread {
                    runCatching { Enforcer.apply(ctx) }
                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                        runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
        finish()
    }

    /** Sends devices/{id}/requests/{domain} for the owner to approve in the parent app. */
    private fun sendRequest() {
        val host = Domains.hostOf(requestUrl)
        if (host == null) {
            requestState = "error"
            return
        }
        val id = Prefs(this).deviceId ?: run { requestState = "error"; return }
        requestState = "sending"
        val url = requestUrl.trim().take(500)
        Repo.device(id).collection("requests").document(host).set(
            mapOf(
                "domain" to host,
                "url" to url,
                "status" to "pending",
                "ts" to FieldValue.serverTimestamp(),
            )
        ).addOnSuccessListener { requestState = "sent" }
            .addOnFailureListener { requestState = "error" }
    }

    @Composable
    private fun Screen() {
        val (icon, title) = when (reason) {
            "pin" -> "🔒" to "هذا التطبيق مقفل"
            "ytsettings" -> "🔒" to "إعدادات وسجلّ يوتيوب مقفلة"
            "limit" -> "⏳" to "انتهى وقت الاستخدام لهذا اليوم"
            "bedtime" -> "🌙" to "حان وقت النوم"
            "paused" -> "⏸" to "الأجهزة متوقفة مؤقتاً"
            "hold" -> "🔒" to "الجهاز مقفل من قبل المالك"
            "browser" -> "🌐" to "هذا المتصفح غير مسموح"
            "web" -> "🚫" to "هذا الموقع غير مسموح"
            "request" -> "📨" to "طلب السماح بموقع"
            else -> "🚫" to "هذا التطبيق محظور"
        }
        val name = when (reason) {
            "web" -> target
            "request", "hold" -> ""
            "ytsettings" -> "أدخل رمز المالك لفتحها ${Guard.policy.unlockMinutes} دقيقة"
            "browser" -> appLabel(target) + " — استعمل متصفحاً مدعوماً مثل Chrome"
            else -> appLabel(target)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(icon, fontSize = 56.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (name.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(24.dp))

                    if (isWeb) RequestBox()

                    if (reason == "hold") {
                        Text("سيُرفع القفل عندما يسمح المالك من تطبيقه.", fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        if (showPin) {
                            PinPad(error) { submit(it) }
                        } else {
                            TextButton(onClick = { showPin = true }) { Text("🔑 رمز المالك") }
                        }
                    } else if (reason != "request") {
                        if (reason == "pin" || reason == "ytsettings" || showPin) {
                            if (reason != "pin" && reason != "ytsettings") {
                                Text("يمكن للمالك السماح مؤقتاً لمدة ${Guard.policy.overrideMinutes} دقيقة", fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                            }
                            PinPad(error) { submit(it) }
                            Spacer(Modifier.height(16.dp))
                        } else {
                            TextButton(onClick = { showPin = true }) { Text("🔑 رمز المالك") }
                        }
                        OutlinedButton(onClick = { goHome() }) { Text("رجوع") }
                    }
                }
            }
        }
    }

    @Composable
    private fun RequestBox() {
        Text(
            if (reason == "request") "اكتب أو الصق رابط الموقع الذي تريد زيارته، وسيصل الطلب إلى المالك."
            else "يمكنك طلب السماح بهذا الموقع من المالك:",
            fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            requestUrl, { requestUrl = it; if (requestState != "sending") requestState = "" },
            label = { Text("رابط الموقع") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        when (requestState) {
            "sent" -> Text("✅ تم إرسال الطلب. حاول مرة أخرى بعد موافقة المالك.", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            "error" -> Text("تعذّر الإرسال. تأكد من الرابط والاتصال بالإنترنت.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (requestState != "sent") {
            Button(
                onClick = { sendRequest() },
                enabled = requestState != "sending" && requestUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (requestState == "sending") "جارٍ الإرسال..." else "📨 اطلب السماح") }
        }
        Spacer(Modifier.height(20.dp))
    }

    companion object {
        fun show(ctx: Context, target: String, reason: String, url: String? = null) {
            val i = Intent(ctx, LockActivity::class.java)
                .putExtra("t", target)
                .putExtra("r", reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (url != null) i.putExtra("u", url)
            runCatching { ctx.startActivity(i) }
        }
    }
}
