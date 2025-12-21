package com.example.mindmetrics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private val usagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAndNotifyUsagePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // ENABLE NECESSARY WEBVIEW SETTINGS
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("wv", "") + " Chrome/119 Mobile"
        }

        // INTERCEPT GOOGLE AUTH URL AND OPEN CUSTOM TAB
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // 🚀 FORCE Google Sign-In outside WebView
                if (url.contains("accounts.google.com") ||
                    url.contains("oauth") ||
                    url.contains("/__/auth/handler"))
                {
                    openCustomTab(url)
                    return true
                }
                return false
            }
        }

        // FILE UPLOAD SUPPORT
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallbackParam: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePathCallbackParam

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                return true
            }
        }

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            filePathCallback?.let { callback ->
                if (result.resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null) {
                        val uris = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        callback.onReceiveValue(uris)
                    } else {
                        val uri = data.data
                        callback.onReceiveValue(if (uri != null) arrayOf(uri) else null)
                    }
                } else callback.onReceiveValue(null)
            }
            filePathCallback = null
        }

        webView.addJavascriptInterface(
            AndroidBridge(this, ::checkAndNotifyUsagePermissionStatus, usagePermissionLauncher),
            "Android"
        )

        webView.loadUrl("https://stresspredictapp.web.app/")
        setupOnBackPressed()
    }

    private fun setupOnBackPressed() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun openCustomTab(url: String) {
        val intent = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setShowTitle(true)
            .build()

        intent.launchUrl(this, Uri.parse(url))
    }

    private fun checkAndNotifyUsagePermissionStatus() {
        val hasPermission = AndroidBridge.hasUsagePermission(this)
        val status = if (hasPermission) "granted" else "denied"

        webView.post {
            webView.evaluateJavascript("window.permissionStatusCallback('$status');", null)
        }
    }

    class AndroidBridge(
        private val context: Context,
        private val permissionStatusChecker: () -> Unit,
        private val launcher: ActivityResultLauncher<Intent>
    ) {

        companion object {
            fun hasUsagePermission(context: Context): Boolean {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

                val mode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            context.packageName
                        )
                    else
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            context.packageName
                        )

                return mode == AppOpsManager.MODE_ALLOWED
            }
        }

        @android.webkit.JavascriptInterface
        fun requestUsagePermission() {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            launcher.launch(intent)
        }

        // 🔥 EXACT SCREEN TIME AS IN DIGITAL WELLBEING
        @android.webkit.JavascriptInterface
        fun getScreenTime(): String {
            val result = JSONObject()
            try {
                if (!hasUsagePermission(context)) {
                    result.put("error", "no_permission")
                    return result.toString()
                }

                val usageStatsManager =
                    context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)

                val start = calendar.timeInMillis
                val end = System.currentTimeMillis()

                val events = usageStatsManager.queryEvents(start, end)
                val event = UsageEvents.Event()

                val wa = mutableListOf<Pair<Long, Long>>()
                val ig = mutableListOf<Pair<Long, Long>>()
                val yt = mutableListOf<Pair<Long, Long>>()
                val active = mutableMapOf<String, Long>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED,
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> active[event.packageName] =
                            event.timeStamp

                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            val startTime = active[event.packageName] ?: continue
                            val session = startTime to event.timeStamp
                            when (event.packageName) {
                                "com.whatsapp" -> wa += session
                                "com.instagram.android" -> ig += session
                                "com.google.android.youtube" -> yt += session
                            }
                            active.remove(event.packageName)
                        }
                    }
                }

                val now = System.currentTimeMillis()
                active.forEach { (pkg, t) ->
                    val session = t to now
                    when (pkg) {
                        "com.whatsapp" -> wa += session
                        "com.instagram.android" -> ig += session
                        "com.google.android.youtube" -> yt += session
                    }
                }

                fun total(list: MutableList<Pair<Long, Long>>) =
                    list.sumOf { it.second - it.first } / 60000

                result.put("whatsapp", total(wa))
                result.put("instagram", total(ig))
                result.put("youtube", total(yt))
                result.put("permission", "granted")

            } catch (e: Exception) {
                result.put("error", "exception")
                result.put("message", e.message)
            }
            return result.toString()
        }
    }
}
