package com.dsfree.api.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动代理服务
        startProxyService()
        // 请求忽略电池优化
        requestBatteryOptimization()

        // 创建 WebView
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = AdminWebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(webView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 从通知点击进入时，确保加载管理面板
        if (isServiceRunning) {
            loadAdminPanel()
        }
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
    }

    private fun loadAdminPanel() {
        webView.loadUrl("http://127.0.0.1:22217/admin")
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // 某些设备不支持，静默忽略
                }
            }
        }
    }

    private inner class AdminWebViewClient : WebViewClient() {
        private var retryCount = 0
        private val maxRetries = 30

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            // 服务尚未启动完成，稍后重试
            if (retryCount < maxRetries) {
                retryCount++
                view?.postDelayed({ loadAdminPanel() }, 1000)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            retryCount = maxRetries // 加载成功，停止重试
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            // 只允许加载本地管理面板
            if (url.startsWith("http://127.0.0.1:22217") || url.startsWith("http://localhost:22217")) {
                return false
            }
            // 阻止外部链接
            return true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不停止服务，让通知栏的停止按钮来控制
    }
}