package com.dsfree.api.android

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DSFreeAPI"
        private const val ADMIN_URL = "http://127.0.0.1:22217/admin"
        private const val HEALTH_URL = "http://127.0.0.1:22217/health"
        private const val MAX_RETRIES = 90
        private const val POLL_INTERVAL_MS = 1000L
        private const val PAGE_LOAD_TIMEOUT_MS = 15000L
    }

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var healthCheckThread: Thread? = null
    private var isHealthChecking = false
    private var isWebViewVisible = false
    private var pageLoadTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        startProxyService()
        requestBatteryOptimization()
        startHealthCheck()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        loadingLayout = findViewById(R.id.loadingLayout)
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener {
            showError(false)
            startProxyService()
            startHealthCheck()
        }
    }

    private fun setupWebView() {
        // 硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // 允许跨域请求到本地服务
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
        }

        // 深色模式支持
        applyDarkMode()

        webView.webViewClient = AdminWebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress > 50 && !isWebViewVisible) {
                    showWebView()
                }
            }
        }
    }

    private fun applyDarkMode() {
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            // 优先使用 Algorithmic Darkening (API 29+)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
                Log.i(TAG, "深色模式: Algorithmic Darkening 已启用")
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
                Log.i(TAG, "深色模式: Force Dark 已启用")
            }

            // 设置 WebView 背景为深色
            webView.setBackgroundColor(Color.parseColor("#121212"))
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            webView.setBackgroundColor(Color.WHITE)
        }
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startHealthCheck() {
        if (isHealthChecking) return
        isHealthChecking = true
        showLoading()

        healthCheckThread = Thread {
            var retryCount = 0
            while (retryCount < MAX_RETRIES && isHealthChecking && !isFinishing) {
                try {
                    val url = URL(HEALTH_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.requestMethod = "GET"
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false

                    val responseCode = connection.responseCode
                    connection.disconnect()

                    if (responseCode == 200) {
                        Log.i(TAG, "健康检查通过 (${retryCount + 1}次尝试)")
                        handler.post {
                            isHealthChecking = false
                            loadAdminPanel()
                        }
                        return
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "健康检查 (${retryCount + 1}/$MAX_RETRIES): ${e.message}")
                }

                retryCount++

                // 每 15 次更新加载提示
                if (retryCount % 15 == 0) {
                    handler.post {
                        loadingText.text = "正在启动 DeepSeek 代理服务... (${retryCount}s)"
                    }
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return
                }
            }

            if (isHealthChecking && !isFinishing) {
                handler.post {
                    isHealthChecking = false
                    showError(true)
                }
            }
        }.also { it.isDaemon = true }
        healthCheckThread?.start()
    }

    private fun loadAdminPanel() {
        runOnUiThread {
            // 设置页面加载超时
            pageLoadTimeoutRunnable = Runnable {
                if (!isWebViewVisible) {
                    Log.w(TAG, "WebView 页面加载超时")
                    showError(true)
                }
            }
            handler.postDelayed(pageLoadTimeoutRunnable!!, PAGE_LOAD_TIMEOUT_MS)

            webView.loadUrl(ADMIN_URL)
            Log.i(TAG, "正在加载管理面板: $ADMIN_URL")
        }
    }

    private fun showLoading() {
        runOnUiThread {
            loadingLayout.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            loadingText.visibility = View.VISIBLE
            loadingText.text = getString(R.string.loading_text)
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            webView.visibility = View.GONE
            isWebViewVisible = false
        }
    }

    private fun showError(show: Boolean) {
        runOnUiThread {
            if (show) {
                progressBar.visibility = View.GONE
                loadingText.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.VISIBLE
                loadingText.visibility = View.VISIBLE
                errorText.visibility = View.GONE
                retryButton.visibility = View.GONE
            }
        }
    }

    private fun showWebView() {
        runOnUiThread {
            pageLoadTimeoutRunnable?.let { handler.removeCallbacks(it) }
            loadingLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            isWebViewVisible = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isWebViewVisible) {
            loadAdminPanel()
        } else if (!isHealthChecking) {
            startHealthCheck()
        }
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
                    Log.w(TAG, "请求电池优化豁免失败: ${e.message}")
                }
            }
        }
    }

    private inner class AdminWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.i(TAG, "页面加载完成: $url")
            showWebView()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            Log.e(TAG, "WebView 错误: ${error?.description} (code=${error?.errorCode}) url=${request?.url}")

            // 仅对主框架错误显示错误页
            if (request?.isForMainFrame == true && !isWebViewVisible) {
                showError(true)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
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
        isHealthChecking = false
        healthCheckThread?.interrupt()
        pageLoadTimeoutRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}
