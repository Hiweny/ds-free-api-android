package com.dsfree.api.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewSwitcher
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

    private lateinit var viewSwitcher: ViewSwitcher
    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var debugScrollView: ScrollView
    private lateinit var debugText: TextView
    private lateinit var copyButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var healthCheckThread: Thread? = null
    private var isHealthChecking = false
    private var isWebViewVisible = false
    private var pageLoadTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 适配沉浸式状态栏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        startProxyService()
        requestBatteryOptimization()
        startHealthCheck()
    }

    private fun initViews() {
        viewSwitcher = findViewById(R.id.viewSwitcher)
        webView = findViewById(R.id.webView)
        loadingLayout = findViewById(R.id.loadingLayout)
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        debugScrollView = findViewById(R.id.debugScrollView)
        debugText = findViewById(R.id.debugText)
        copyButton = findViewById(R.id.copyButton)

        retryButton.setOnClickListener {
            showError(false)
            startProxyService()
            startHealthCheck()
        }

        copyButton.setOnClickListener {
            val debugInfo = debugText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("debug", debugInfo))
            Toast.makeText(this, "调试信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupWebView() {
        // 硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 关键：确保 WebView 可以获取焦点
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus(View.FOCUS_DOWN)

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
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false

            // 启用文本缩放为 100%（某些设备默认缩放导致布局问题）
            textZoom = 100
        }

        // 深色模式
        applyDarkMode()

        webView.webViewClient = AdminWebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress > 50 && !isWebViewVisible) {
                    showWebView()
                }
            }

            // 处理网页请求焦点（输入框点击时）
            override fun onRequestFocus(view: WebView?) {
                view?.requestFocus(View.FOCUS_DOWN)
            }
        }

        // 确保WebView在可见时能获取焦点
        webView.setOnTouchListener { v, _ ->
            if (!v.hasFocus()) {
                v.requestFocus(View.FOCUS_DOWN)
            }
            false  // 不消费事件，让 WebView 正常处理
        }
    }

    private fun applyDarkMode() {
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
                Log.i(TAG, "深色模式: Algorithmic Darkening 已启用")
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
                Log.i(TAG, "深色模式: Force Dark 已启用")
            }
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
                val svcError = ProxyService.serviceError
                if (svcError != null) {
                    Log.e(TAG, "ProxyService 报告错误: $svcError")
                    handler.post {
                        isHealthChecking = false
                        showErrorWithDebug(svcError, ProxyService.processOutput)
                    }
                    break
                }

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
                        isHealthChecking = false
                        handler.post {
                            loadAdminPanel()
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "健康检查 (${retryCount + 1}/$MAX_RETRIES): ${e.message}")
                }

                retryCount++

                if (retryCount % 10 == 0) {
                    handler.post {
                        loadingText.text = "正在启动 DeepSeek 代理服务... (${retryCount}s)"
                    }
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    isHealthChecking = false
                    break
                }
            }

            if (isHealthChecking && !isFinishing) {
                handler.post {
                    isHealthChecking = false
                    val svcError = ProxyService.serviceError
                    if (svcError != null) {
                        showErrorWithDebug(svcError, ProxyService.processOutput)
                    } else {
                        showErrorWithDebug(
                            "代理服务未能在 ${MAX_RETRIES} 秒内启动。",
                            ProxyService.processOutput
                        )
                    }
                }
            }
        }.also { it.isDaemon = true }
        healthCheckThread?.start()
    }

    private fun loadAdminPanel() {
        runOnUiThread {
            pageLoadTimeoutRunnable = Runnable {
                if (!isWebViewVisible) {
                    Log.w(TAG, "WebView 页面加载超时")
                    showErrorWithDebug("页面加载超时", ProxyService.processOutput)
                }
            }
            handler.postDelayed(pageLoadTimeoutRunnable!!, PAGE_LOAD_TIMEOUT_MS)

            webView.loadUrl(ADMIN_URL)
            Log.i(TAG, "正在加载管理面板: $ADMIN_URL")
        }
    }

    private fun showLoading() {
        runOnUiThread {
            if (viewSwitcher.displayedChild != 0) {
                viewSwitcher.displayedChild = 0
            }
            progressBar.visibility = View.VISIBLE
            loadingText.visibility = View.VISIBLE
            loadingText.text = getString(R.string.loading_text)
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            debugScrollView.visibility = View.GONE
            copyButton.visibility = View.GONE
            isWebViewVisible = false
        }
    }

    private fun showError(show: Boolean) {
        runOnUiThread {
            if (viewSwitcher.displayedChild != 0) {
                viewSwitcher.displayedChild = 0
            }
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
                debugScrollView.visibility = View.GONE
                copyButton.visibility = View.GONE
            }
        }
    }

    private fun showErrorWithDebug(error: String, debug: String) {
        runOnUiThread {
            if (viewSwitcher.displayedChild != 0) {
                viewSwitcher.displayedChild = 0
            }
            progressBar.visibility = View.GONE
            loadingText.visibility = View.GONE
            errorText.visibility = View.VISIBLE
            errorText.text = error
            retryButton.visibility = View.VISIBLE

            if (debug.isNotEmpty()) {
                debugText.text = debug
                debugScrollView.visibility = View.VISIBLE
                copyButton.visibility = View.VISIBLE
            }
        }
    }

    private fun showWebView() {
        runOnUiThread {
            pageLoadTimeoutRunnable?.let { handler.removeCallbacks(it) }
            // 切换到 WebView（index 1），彻底移除 loading 层
            if (viewSwitcher.displayedChild != 1) {
                viewSwitcher.displayedChild = 1
            }
            isWebViewVisible = true
            // 确保 WebView 获取焦点
            webView.requestFocus(View.FOCUS_DOWN)
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
            // 注入 CSS 修复可能的 viewport 问题
            view?.evaluateJavascript(
                """
                (function() {
                    // 确保 viewport meta 标签正确
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        document.head.appendChild(meta);
                    }
                    // 确保所有 input 可以获取焦点
                    document.body.style.webkitTapHighlightColor = 'transparent';
                })();
                """.trimIndent(),
                null
            )
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            Log.e(TAG, "WebView 错误: ${error?.description} (code=${error?.errorCode}) url=${request?.url}")

            if (request?.isForMainFrame == true && !isWebViewVisible) {
                showErrorWithDebug(
                    "页面加载失败: ${error?.description}",
                    ProxyService.processOutput
                )
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            if (url.startsWith("http://127.0.0.1:22217") || url.startsWith("http://localhost:22217")) {
                return false
            }
            return true
        }
    }

    // 处理返回键：WebView 有历史记录时后退
    override fun onBackPressed() {
        if (isWebViewVisible && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isHealthChecking = false
        healthCheckThread?.interrupt()
        pageLoadTimeoutRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }
}
