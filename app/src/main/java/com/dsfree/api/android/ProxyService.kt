package com.dsfree.api.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ProxyService : Service() {

    companion object {
        private const val TAG = "DSFreeAPI"
        private const val BINARY_NAME = "libdsfreeapi.so"
        const val CONFIG_NAME = "config.toml"
        const val ACTION_START = "com.dsfree.api.android.ACTION_START"
        const val ACTION_PAUSE = "com.dsfree.api.android.ACTION_PAUSE"
        const val ACTION_RESUME = "com.dsfree.api.android.ACTION_RESUME"
        const val ACTION_STOP = "com.dsfree.api.android.ACTION_STOP"

        private const val MAX_RESTART_COUNT = 5
        private const val RESTART_DELAY_MS = 3000L
    }

    private var proxyProcess: Process? = null
    private lateinit var notificationHelper: NotificationHelper
    private var isRunning = false
    private var restartCount = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_PAUSE -> pauseProxy()
            ACTION_RESUME -> resumeProxy()
            ACTION_STOP -> stopProxy()
            else -> startProxy()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        if (isRunning) return

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.createRunningNotification()
        )

        restartCount = 0

        serviceScope.launch {
            try {
                val binaryFile = resolveBinary()
                prepareConfig()
                launchBinary(binaryFile)
                isRunning = true
                Log.i(TAG, "ds-free-api 代理服务已启动")
            } catch (e: Exception) {
                Log.e(TAG, "启动代理服务失败", e)
                stopSelf()
            }
        }
    }

    /**
     * 从 nativeLibraryDir 获取已提取的二进制文件。
     * Android 10+ 禁止从 filesDir 执行二进制 (W^X 限制)，
     * 必须使用 jniLibs 打包的二进制（自动提取到 nativeLibraryDir）。
     */
    private fun resolveBinary(): File {
        val nativeDir = applicationInfo.nativeLibraryDir
        val binaryFile = File(nativeDir, BINARY_NAME)

        if (binaryFile.exists() && binaryFile.canExecute()) {
            Log.i(TAG, "二进制文件就绪: ${binaryFile.absolutePath} (${binaryFile.length()} bytes)")
            return binaryFile
        }

        // 极端 fallback：尝试从 assets 拷贝到 filesDir（Android 9 及以下可用）
        Log.w(TAG, "nativeLibraryDir 中未找到二进制，尝试 assets fallback...")
        val fallback = File(filesDir, BINARY_NAME)
        if (fallback.exists() && fallback.canExecute()) {
            Log.w(TAG, "使用 assets fallback: ${fallback.absolutePath}")
            return fallback
        }

        try {
            assets.open(BINARY_NAME).use { input ->
                FileOutputStream(fallback).use { output ->
                    input.copyTo(output)
                }
            }
            fallback.setExecutable(true, true)
            fallback.setReadable(true, true)
            fallback.setWritable(true, true)
            Log.w(TAG, "已从 assets 拷贝到 filesDir: ${fallback.absolutePath}")
            return fallback
        } catch (e: IOException) {
            throw RuntimeException(
                "无法定位二进制文件。nativeLibraryDir=$nativeDir, " +
                "fallback=${fallback.absolutePath}, error=${e.message}", e
            )
        }
    }

    private fun prepareConfig() {
        val configFile = File(filesDir, CONFIG_NAME)

        if (configFile.exists()) {
            Log.d(TAG, "配置文件已存在: ${configFile.absolutePath}")
            return
        }

        try {
            assets.open("config.example.toml").use { input ->
                FileOutputStream(configFile).use { output ->
                    input.copyTo(output)
                }
            }
            configFile.setReadable(true, true)
            configFile.setWritable(true, true)
            Log.i(TAG, "默认配置文件已创建: ${configFile.absolutePath}")
        } catch (e: IOException) {
            // 如果 assets 中没有，创建最小配置
            configFile.writeText(
                """
                [server]
                host = "127.0.0.1"
                port = 22217
                cors_origins = ["http://localhost:22217", "http://127.0.0.1:22217"]

                [ds_core]

                [proxy]

                [admin]
                """.trimIndent()
            )
            configFile.setReadable(true, true)
            configFile.setWritable(true, true)
            Log.w(TAG, "创建了内联最小配置文件: ${configFile.absolutePath}")
        }
    }

    private fun launchBinary(binaryFile: File) {
        val configFile = File(filesDir, CONFIG_NAME)
        val dataDir = filesDir.absolutePath
        val cacheDir = cacheDir.absolutePath

        val command = listOf(
            binaryFile.absolutePath,
            "-c",
            configFile.absolutePath
        )

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(filesDir)

        // 设置运行时环境变量
        processBuilder.environment().apply {
            put("DS_DATA_DIR", dataDir)
            put("DS_CONFIG_PATH", configFile.absolutePath)
            put("RUST_LOG", "info")
            put("HOME", dataDir)
            put("TMPDIR", cacheDir)
            put("SSL_CERT_FILE", "/system/etc/security/cacerts/")
        }

        processBuilder.redirectErrorStream(true)

        try {
            proxyProcess = processBuilder.start()
            Log.i(TAG, "已启动进程: PID=${proxyProcess?.pid()}, binary=${binaryFile.name}")

            // 读取进程输出用于日志
            serviceScope.launch {
                try {
                    proxyProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.i(TAG, "[ds-free-api] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取进程输出异常: ${e.message}")
                }
            }

            // 监控进程退出
            serviceScope.launch {
                try {
                    val exitCode = proxyProcess?.waitFor() ?: -1
                    Log.w(TAG, "ds-free-api 进程退出，退出码: $exitCode")
                    isRunning = false

                    if (exitCode != 0 && restartCount < MAX_RESTART_COUNT) {
                        restartCount++
                        Log.i(TAG, "尝试重启 ds-free-api (第 $restartCount 次)...")
                        delay(RESTART_DELAY_MS)
                        if (!isRunning) {
                            launchBinary(binaryFile)
                        }
                    } else if (restartCount >= MAX_RESTART_COUNT) {
                        Log.e(TAG, "已达最大重启次数 ($MAX_RESTART_COUNT)，停止重启")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "进程监控异常", e)
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("无法启动二进制文件: ${e.message}", e)
        }
    }

    private fun pauseProxy() {
        if (!isRunning) return
        proxyProcess?.destroy()
        proxyProcess = null
        isRunning = false

        val notification = notificationHelper.createPausedNotification()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID, notification)

        Log.i(TAG, "代理服务已暂停")
    }

    private fun resumeProxy() {
        if (isRunning) return
        startProxy()
    }

    private fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess = null
        isRunning = false
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "代理服务已停止")
    }

    override fun onDestroy() {
        proxyProcess?.destroy()
        proxyProcess = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
