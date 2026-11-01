package com.familyguard.parent

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Lock screen of the parent app.
 *  mode "unlock"   – PIN or fingerprint to open the app
 *  mode "setup"    – first launch: create the PIN
 *  mode "settings" – change the PIN / turn fingerprint on or off (opened from the dashboard 🔒 button)
 */
class UnlockActivity : FragmentActivity() {

    private lateinit var root: LinearLayout
    private val green = Color.parseColor("#1F6F5C")

    private val mode: String
        get() = when {
            intent.getStringExtra(EXTRA_MODE) == MODE_SETTINGS -> MODE_SETTINGS
            !AppLock.hasPin(this) -> MODE_SETUP
            else -> MODE_UNLOCK
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(48), dp(28), dp(28))
            setBackgroundColor(Color.WHITE)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_CANCELED)
                finish()
            }
        })
        when (mode) {
            MODE_SETUP -> buildPinForm(setup = true)
            MODE_SETTINGS -> buildPinForm(setup = false)
            else -> buildUnlock()
        }
    }

    // ---------------- UI helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#17201D")) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        gravity = Gravity.CENTER
        textSize = 22f
        layoutParams = LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        if (primary) {
            setBackgroundColor(green)
            setTextColor(Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun canUseBiometric() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    // ---------------- Unlock ----------------

    private fun buildUnlock() {
        val error = text("", 14f, color = Color.parseColor("#B3261E"))
        val pin = pinField("رمز الدخول")
        root.addView(text("🛡️", 48f))
        root.addView(text("Zemory Family Guard", 22f, bold = true))
        root.addView(text("أدخل رمز الدخول", 16f))
        root.addView(pin)
        root.addView(error)
        root.addView(button("دخول") {
            if (AppLock.checkPin(this, pin.text.toString())) unlocked()
            else {
                error.text = "رمز غير صحيح"
                pin.text.clear()
            }
        })
        if (AppLock.biometricEnabled(this) && canUseBiometric()) {
            root.addView(button("🔐 الدخول بالبصمة", primary = false) { showBiometric() })
            root.post { showBiometric() }
        }
        root.addView(TextView(this).apply {
            text = "نسيت الرمز؟"
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setOnClickListener { forgot() }
        })
    }

    private fun showBiometric() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlocked()
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Zemory Family Guard")
            .setSubtitle("الدخول بالبصمة")
            .setNegativeButtonText("استعمال الرمز")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()
        runCatching { prompt.authenticate(info) }
    }

    private fun forgot() {
        AlertDialog.Builder(this)
            .setTitle("نسيت رمز الدخول؟")
            .setMessage("سيتم حذف الرمز وتسجيل الخروج من حسابك. بعدها تنشئ رمزاً جديداً وتدخل بالبريد وكلمة المرور.")
            .setPositiveButton("متابعة") { _, _ ->
                AppLock.reset(this)
                recreate()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun unlocked() {
        AppLock.markUnlocked()
        setResult(RESULT_OK)
        finish()
    }

    // ---------------- Setup / change PIN ----------------

    private fun buildPinForm(setup: Boolean) {
        val error = text("", 14f, color = Color.parseColor("#B3261E"))
        val p1 = pinField("الرمز الجديد (4–8 أرقام)")
        val p2 = pinField("أعد كتابة الرمز")
        val bio = CheckBox(this).apply {
            text = "الدخول بالبصمة أيضاً"
            textSize = 16f
            isChecked = if (setup) true else AppLock.biometricEnabled(this@UnlockActivity)
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        root.addView(text("🔒", 44f))
        root.addView(text(if (setup) "إنشاء رمز دخول للتطبيق" else "قفل التطبيق", 20f, bold = true))
        root.addView(text(
            if (setup) "سيُطلب هذا الرمز (أو البصمة) كلما فتحت التطبيق." else "اترك الخانتين فارغتين للإبقاء على الرمز الحالي.",
            14f, color = Color.parseColor("#5D6B66"),
        ))
        root.addView(p1)
        root.addView(p2)
        if (canUseBiometric()) root.addView(bio)
        root.addView(error)
        root.addView(button(if (setup) "حفظ ومتابعة" else "حفظ") {
            val a = p1.text.toString()
            val b = p2.text.toString()
            if (setup || a.isNotEmpty() || b.isNotEmpty()) {
                if (!Regex("^[0-9]{4,8}$").matches(a)) {
                    error.text = "الرمز من 4 إلى 8 أرقام"
                    return@button
                }
                if (a != b) {
                    error.text = "الرمزان غير متطابقين"
                    return@button
                }
                AppLock.setPin(this, a)
            }
            AppLock.setBiometric(this, canUseBiometric() && bio.isChecked)
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            unlocked()
        })
        if (!setup) root.addView(button("إلغاء", primary = false) {
            setResult(RESULT_CANCELED)
            finish()
        })
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_SETUP = "setup"
        const val MODE_SETTINGS = "settings"
    }
}
