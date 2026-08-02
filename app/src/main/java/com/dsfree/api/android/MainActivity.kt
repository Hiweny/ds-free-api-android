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

        /**
         * 移动端适配注入脚本 (CSS + JS)。
         *
         * ds-free-api 管理面板基于 React + Tailwind CSS v4 + shadcn/ui 构建，
         * 原始布局为桌面端设计：
         *   <div class="min-h-screen flex">
         *     <aside class="w-56 border-r bg-card flex flex-col">侧边栏</aside>
         *     <main class="flex-1 overflow-auto">
         *       <div class="p-6 w-full">页面内容</div>
         *     </main>
         *   </div>
         *
         * 适配策略：
         * 1. CSS：侧边栏 → 固定定位抽屉，主内容 → 全宽，表格 → 水平滚动，
         *    Flex 布局 → 允许换行，字号/间距 → 移动端友好尺寸
         * 2. JS：创建浮动菜单按钮 + 半透明遮罩，点击按钮切换侧边栏开/关，
         *    使用 MutationObserver 监听 React SPA 导航导致的 DOM 重建，
         *    自动在登录页隐藏按钮、在管理页显示按钮
         */
        private val MOBILE_ADAPT_SCRIPT = """
(function() {
    'use strict';
    if (window.__mobileAdaptInit) return;
    window.__mobileAdaptInit = true;

    // ==================== 1. 注入 CSS ====================
    var style = document.createElement('style');
    style.id = 'mobile-adapt-css';
    style.textContent = [
        '@media (max-width: 768px) {',
        '  /* === 防止水平溢出 === */',
        '  html, body { overflow-x: hidden !important; max-width: 100vw !important; }',
        '',
        '  /* === 侧边栏：固定抽屉 === */',
        '  aside.w-56 {',
        '    position: fixed !important;',
        '    left: -280px !important;',
        '    top: 0 !important;',
        '    bottom: 0 !important;',
        '    height: 100dvh !important;',
        '    width: 260px !important;',
        '    max-width: 85vw !important;',
        '    z-index: 10000 !important;',
        '    transition: left 0.3s cubic-bezier(0.4, 0, 0.2, 1) !important;',
        '    box-shadow: 2px 0 16px rgba(0,0,0,0.25) !important;',
        '  }',
        '  aside.w-56.mobile-open { left: 0 !important; }',
        '',
        '  /* === 主内容区：全宽 === */',
        '  main.flex-1 {',
        '    width: 100% !important;',
        '    max-width: 100% !important;',
        '    overflow-x: hidden !important;',
        '  }',
        '  main.flex-1 > div {',
        '    padding: 10px !important;',
        '    padding-top: 50px !important;',
        '  }',
        '',
        '  /* === 浮动菜单按钮 === */',
        '  .mobile-menu-btn {',
        '    position: fixed !important;',
        '    top: 6px !important;',
        '    left: 6px !important;',
        '    z-index: 9999 !important;',
        '    width: 36px !important;',
        '    height: 36px !important;',
        '    border-radius: 8px !important;',
        '    background: var(--primary, oklch(0.205 0 0)) !important;',
        '    color: var(--primary-foreground, oklch(0.985 0 0)) !important;',
        '    display: flex !important;',
        '    align-items: center !important;',
        '    justify-content: center !important;',
        '    cursor: pointer !important;',
        '    border: none !important;',
        '    padding: 0 !important;',
        '    box-shadow: 0 2px 8px rgba(0,0,0,0.2) !important;',
        '    -webkit-tap-highlight-color: transparent !important;',
        '    user-select: none !important;',
        '  }',
        '  .mobile-menu-btn:active { opacity: 0.7 !important; }',
        '',
        '  /* === 遮罩层 === */',
        '  .mobile-overlay {',
        '    position: fixed !important;',
        '    top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important;',
        '    background: rgba(0,0,0,0.5) !important;',
        '    z-index: 9997 !important;',
        '    opacity: 0 !important;',
        '    pointer-events: none !important;',
        '    transition: opacity 0.3s ease !important;',
        '  }',
        '  .mobile-overlay.visible {',
        '    opacity: 1 !important;',
        '    pointer-events: auto !important;',
        '  }',
        '',
        '  /* === 表格：水平滚动 === */',
        '  [class*="overflow-auto"] {',
        '    max-width: 100% !important;',
        '    overflow-x: auto !important;',
        '    -webkit-overflow-scrolling: touch !important;',
        '  }',
        '  table { width: 100% !important; }',
        '',
        '  /* === Flex 布局：允许换行 === */',
        '  main.flex-1 .flex.gap-6 {',
        '    flex-wrap: wrap !important;',
        '    gap: 12px !important;',
        '    justify-content: space-around !important;',
        '  }',
        '  main.flex-1 .flex.gap-4 { flex-wrap: wrap !important; }',
        '',
        '  /* === 字号适配 === */',
        '  main.flex-1 .text-2xl { font-size: 1.125rem !important; }',
        '  main.flex-1 .text-3xl { font-size: 1.5rem !important; }',
        '  main.flex-1 h1 { font-size: 1.125rem !important; }',
        '',
        '  /* === Card 内边距 === */',
        '  main.flex-1 [class*="CardContent"] { padding: 10px !important; }',
        '  main.flex-1 [class*="CardHeader"] { padding: 10px !important; }',
        '',
        '  /* === 触摸目标 === */',
        '  main.flex-1 button { min-height: 36px !important; }',
        '  main.flex-1 nav a { min-height: 40px !important; }',
        '',
        '  /* === 防止 iOS 输入框聚焦缩放 === */',
        '  input, select, textarea { font-size: 16px !important; }',
        '',
        '  /* === 账号池项目 === */',
        '  main.flex-1 .text-center { min-width: 56px !important; }',
        '',
        '  /* === 登录页：保持居中 === */',
        '  .min-h-screen.flex.items-center.justify-center { padding: 16px !important; }',
        '}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);

    // ==================== 2. 创建 UI 元素 ====================
    // 浮动菜单按钮 (SVG 汉堡图标)
    var menuBtn = document.createElement('button');
    menuBtn.id = 'mobile-menu-btn';
    menuBtn.className = 'mobile-menu-btn';
    menuBtn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>';
    menuBtn.style.display = 'none';
    document.body.appendChild(menuBtn);

    // 半透明遮罩
    var overlay = document.createElement('div');
    overlay.id = 'mobile-overlay';
    overlay.className = 'mobile-overlay';
    document.body.appendChild(overlay);

    // ==================== 3. 侧边栏控制逻辑 ====================
    function findSidebar() {
        return document.querySelector('aside.w-56, aside[class*="w-56"]');
    }

    function closeSidebar() {
        var sb = findSidebar();
        if (sb) sb.classList.remove('mobile-open');
        overlay.classList.remove('visible');
    }

    function openSidebar() {
        var sb = findSidebar();
        if (sb) sb.classList.add('mobile-open');
        overlay.classList.add('visible');
    }

    function toggleSidebar() {
        var sb = findSidebar();
        if (!sb) return;
        if (sb.classList.contains('mobile-open')) {
            closeSidebar();
        } else {
            openSidebar();
        }
    }

    menuBtn.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        toggleSidebar();
    });

    overlay.addEventListener('click', closeSidebar);

    // ==================== 4. 检测侧边栏存在性 (React SPA) ====================
    var lastSidebarState = null;

    function checkSidebar() {
        var sb = findSidebar();
        var hasSidebar = !!sb;

        if (hasSidebar !== lastSidebarState) {
            lastSidebarState = hasSidebar;
            if (hasSidebar) {
                menuBtn.style.display = 'flex';
                // 绑定导航链接点击 → 关闭侧边栏
                if (sb) {
                    var links = sb.querySelectorAll('a');
                    for (var i = 0; i < links.length; i++) {
                        if (!links[i].__mobileAdaptBound) {
                            links[i].__mobileAdaptBound = true;
                            (function(link) {
                                link.addEventListener('click', function() {
                                    setTimeout(closeSidebar, 150);
                                });
                            })(links[i]);
                        }
                    }
                }
            } else {
                // 登录页或无侧边栏页面
                menuBtn.style.display = 'none';
                closeSidebar();
            }
        }
    }

    // MutationObserver：监听 React DOM 重建
    var debounceTimer = null;
    if (typeof MutationObserver !== 'undefined') {
        var observer = new MutationObserver(function() {
            if (debounceTimer) clearTimeout(debounceTimer);
            debounceTimer = setTimeout(checkSidebar, 100);
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    // 初始检查 + 延迟检查 (等待 React 渲染)
    checkSidebar();
    setTimeout(checkSidebar, 200);
    setTimeout(checkSidebar, 500);
    setTimeout(checkSidebar, 1000);
    setTimeout(checkSidebar, 2000);
    setTimeout(checkSidebar, 3000);

    // 屏幕旋转时关闭侧边栏
    window.addEventListener('orientationchange', function() {
        setTimeout(closeSidebar, 300);
    });
})();
        """.trimIndent()
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
            injectMobileAdaptation(view)
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

        /**
         * 注入移动端适配代码。
         *
         * ds-free-api 管理面板是 React + Tailwind CSS 的桌面端 SPA：
         * - 固定宽度侧边栏 (aside.w-56 = 224px) 始终占据屏幕左侧
         * - 主内容区 (main.flex-1) 带有 p-6 (24px) 内边距
         * - 表格、Flex 布局没有移动端响应式适配
         *
         * 本方法注入 CSS + JS，将侧边栏改为可滑出/收起的抽屉式导航，
         * 添加浮动菜单按钮，并优化所有内容区域在手机上的显示。
         */
        private fun injectMobileAdaptation(view: WebView?) {
            view?.evaluateJavascript(MOBILE_ADAPT_SCRIPT, null)
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
