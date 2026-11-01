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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.child.Guard
import com.familyguard.child.enforce.Enforcer
import kotlin.concurrent.thread

/** Full screen shown over a blocked app, a PIN-locked app or a blocked website. */
class LockActivity : ComponentActivity() {

    private var target by mutableStateOf("")
    private var reason by mutableStateOf("blocked")
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
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
        error = null
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun submit(pin: String) {
        if (Guard.policy.pinHash.isEmpty()) {
            error = "لم يتم تعيين رمز الوالد من لوحة التحكم"
            return
        }
        if (!Guard.checkPin(pin)) {
            error = "رمز غير صحيح"
            return
        }
        val now = System.currentTimeMillis()
        if (reason == "pin") {
            Guard.unlockedUntil[target] = now + 10 * 60_000L
        } else {
            Guard.overrideUntil[target] = now + 30 * 60_000L
            val ctx = applicationContext
            val pkg = target
            thread {
                runCatching { Enforcer.apply(ctx) }
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        finish()
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val (icon, title) = when (reason) {
            "pin" -> "🔒" to "هذا التطبيق مقفل"
            "limit" -> "⏳" to "انتهى وقت الاستخدام لهذا اليوم"
            "bedtime" -> "🌙" to "حان وقت النوم"
            "paused" -> "⏸" to "الأجهزة متوقفة مؤقتاً"
            "web" -> "🚫" to "هذا الموقع محظور"
            else -> "🚫" to "هذا التطبيق محظور"
        }
        val name = if (reason == "web") target else appLabel(target)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(icon, fontSize = 56.sp)
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                if (reason != "web") {
                    if (reason != "pin") {
                        Text("يمكن للوالد السماح مؤقتاً لمدة 30 دقيقة", fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                    PinPad(error) { submit(it) }
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedButton(onClick = { goHome() }) { Text("رجوع") }
            }
        }
    }

    companion object {
        fun show(ctx: Context, target: String, reason: String) {
            val i = Intent(ctx, LockActivity::class.java)
                .putExtra("t", target)
                .putExtra("r", reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { ctx.startActivity(i) }
        }
    }
}
