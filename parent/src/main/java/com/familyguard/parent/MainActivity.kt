package com.familyguard.parent

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader

/**
 * Parent app: the same dashboard as the website, bundled in the APK and served from a secure origin,
 * protected by the app PIN / fingerprint.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var unlocking = false
    private var pageLoaded = false

    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        unlocking = false
        if (res.resultCode != RESULT_OK) {
            finishAffinity()
            return@registerForActivityResult
        }
        applyPendingSignOut()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        NotifyService.start(this)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
        }
        // Needed so confirm()/alert() dialogs from the dashboard are shown
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                applyPendingSignOut()
            }
        }
        web.addJavascriptInterface(Bridge(), "ZemoryApp")
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!unlocking && AppLock.isLocked(this)) {
            unlocking = true
            unlockLauncher.launch(Intent(this, UnlockActivity::class.java))
        }
    }

    override fun onStop() {
        super.onStop()
        if (!unlocking) AppLock.markBackground()
    }

    private fun applyPendingSignOut() {
        if (pageLoaded && AppLock.consumeSignOut(this)) {
            web.evaluateJavascript("window.fgSignOut && window.fgSignOut()", null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Called from the dashboard (🔒 button). */
    private inner class Bridge {
        @JavascriptInterface
        fun openSecurity() {
            runOnUiThread {
                settingsLauncher.launch(
                    Intent(this@MainActivity, UnlockActivity::class.java)
                        .putExtra(UnlockActivity.EXTRA_MODE, UnlockActivity.MODE_SETTINGS)
                )
            }
        }
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private val START = Uri.parse("https://$HOST/assets/index.html").toString()
    }
}
