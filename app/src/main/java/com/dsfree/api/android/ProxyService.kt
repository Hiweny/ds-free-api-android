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

        @Volatile
        var serviceError: String? = null
            private set
        @Volatile
        var processOutput: String = ""
            private set
    }

    private var proxyProcess: Process? = null
    private lateinit var notificationHelper: NotificationHelper
    private var isRunning = false
    private var restartCount = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outputBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        serviceError = null
        processOutput = ""
        outputBuffer.clear()
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

        serviceError = null

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.createRunningNotification()
        )

        restartCount = 0

        serviceScope.launch {
            try {
                prepareDataDir()
                val binaryFile = resolveBinary()
                prepareConfig()
                launchBinary(binaryFile)
                isRunning = true
                Log.i(TAG, "ds-free-api 代理服务已启动")
            } catch (e: Exception) {
                Log.e(TAG, "启动代理服务失败", e)
                serviceError = "启动失败: ${e.message}"
                stopSelf()
            }
        }
    }

    /**
     * 创建运行时所需的数据目录。
     * 原项目 main.rs 会在 DS_DATA_DIR/logs/runtime.log 写日志，
     * 如果 logs 目录不存在，二进制会 panic 崩溃。
     */
    private fun prepareDataDir() {
        val dataDir = filesDir
        val logsDir = File(dataDir, "logs")
        if (!logsDir.exists()) {
            val created = logsDir.mkdirs()
            Log.i(TAG, "创建日志目录: ${logsDir.absolutePath} (success=$created)")
        }

        val cacheLogsDir = File(cacheDir, "logs")
        if (!cacheLogsDir.exists()) {
            cacheLogsDir.mkdirs()
        }

        Log.i(TAG, "数据目录: ${dataDir.absolutePath}")
        Log.i(TAG, "缓存目录: ${cacheDir.absolutePath}")
    }

    /**
     * 从 nativeLibraryDir 获取已提取的二进制文件。
     * 不检查 canExecute()，因为某些 Android 版本不设置执行位。
     * 尝试主动设置执行权限。
     */
    private fun resolveBinary(): File {
        val nativeDir = applicationInfo.nativeLibraryDir
        val binaryFile = File(nativeDir, BINARY_NAME)

        Log.i(TAG, "nativeLibraryDir: $nativeDir")
        Log.i(TAG, "查找二进制: ${binaryFile.absolutePath}")
        Log.i(TAG, "  exists=${binaryFile.exists()}, size=${binaryFile.length()}")

        if (binaryFile.exists() && binaryFile.length() > 0) {
            // 尝试设置可执行权限（某些设备需要）
            try {
                binaryFile.setExecutable(true, true)
            } catch (e: Exception) {
                Log.w(TAG, "设置执行权限失败 (可忽略): ${e.message}")
            }
            Log.i(TAG, "二进制文件就绪: ${binaryFile.absolutePath} (${binaryFile.length()} bytes, exec=${binaryFile.canExecute()})")
            return binaryFile
        }

        // 列出 nativeLibraryDir 中的所有文件，用于调试
        try {
            val nativeDirFile = File(nativeDir)
            if (nativeDirFile.exists() && nativeDirFile.isDirectory) {
                val files = nativeDirFile.listFiles()
                Log.w(TAG, "nativeLibraryDir 内容 (${files?.size ?: 0} 个文件):")
                files?.forEach { f ->
                    Log.w(TAG, "  ${f.name} (${f.length()} bytes, exec=${f.canExecute()})")
                }
            } else {
                Log.e(TAG, "nativeLibraryDir 不存在或不是目录: $nativeDir")
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出 nativeLibraryDir 失败", e)
        }

        // fallback: 尝试从 assets 拷贝到 filesDir
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
            val errorMsg = "无法定位二进制文件。\n" +
                    "nativeLibraryDir=$nativeDir\n" +
                    "binary exists=${binaryFile.exists()}\n" +
                    "assets fallback failed: ${e.message}"
            Log.e(TAG, errorMsg)
            throw RuntimeException(errorMsg, e)
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

    private fun appendOutput(line: String) {
        Log.i(TAG, "[ds-free-api] $line")
        synchronized(outputBuffer) {
            if (outputBuffer.length > 20000) {
                outputBuffer.setLength(0)
            }
            outputBuffer.appendLine(line)
            processOutput = outputBuffer.toString()
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

        processBuilder.environment().apply {
            put("DS_DATA_DIR", dataDir)
            put("DS_CONFIG_PATH", configFile.absolutePath)
            put("RUST_LOG", "info")
            put("HOME", dataDir)
            put("TMPDIR", cacheDir)
            // Android 系统证书路径
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("SSL_CERT_FILE", "/system/etc/security/cacerts/ca-certificates.crt")
        }

        processBuilder.redirectErrorStream(true)

        try {
            proxyProcess = processBuilder.start()
            Log.i(TAG, "已启动进程: binary=${binaryFile.name}, path=${binaryFile.absolutePath}")
            appendOutput("=== 进程已启动 ===")
            appendOutput("二进制路径: ${binaryFile.absolutePath}")
            appendOutput("配置路径: ${configFile.absolutePath}")
            appendOutput("数据目录: $dataDir")
        } catch (e: IOException) {
            val errorMsg = "无法启动二进制文件: ${e.message}\n" +
                    "路径: ${binaryFile.absolutePath}\n" +
                    "可执行: ${binaryFile.canExecute()}"
            Log.e(TAG, errorMsg, e)
            serviceError = errorMsg
            throw RuntimeException(errorMsg, e)
        }

        // 读取进程输出
        serviceScope.launch {
            try {
                proxyProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lineSequence().forEach { line ->
                        appendOutput(line)
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
                appendOutput("=== 进程退出，退出码: $exitCode ===")
                isRunning = false

                if (exitCode != 0 && restartCount < MAX_RESTART_COUNT) {
                    restartCount++
                    Log.i(TAG, "尝试重启 ds-free-api (第 $restartCount 次)...")
                    appendOutput(">>> 尝试重启 (第 $restartCount 次)...")
                    delay(RESTART_DELAY_MS)
                    if (!isRunning) {
                        launchBinary(binaryFile)
                    }
                } else if (restartCount >= MAX_RESTART_COUNT) {
                    Log.e(TAG, "已达最大重启次数 ($MAX_RESTART_COUNT)，停止重启")
                    serviceError = "进程在 ${MAX_RESTART_COUNT} 次重启后仍然崩溃。\n" +
                            "退出码: $exitCode\n\n" +
                            "进程输出:\n$processOutput"
                }
            } catch (e: Exception) {
                Log.e(TAG, "进程监控异常", e)
                serviceError = "进程监控异常: ${e.message}"
            }
        }

        // 快速失败检测：2 秒后检查进程是否还活着
        serviceScope.launch {
            delay(2000)
            if (isRunning && proxyProcess?.isAlive == false) {
                val exitCode = proxyProcess?.exitValue() ?: -1
                Log.e(TAG, "进程在 2 秒内退出！退出码: $exitCode")
                serviceError = "进程启动后立即崩溃 (退出码: $exitCode)。\n\n进程输出:\n$processOutput"
                isRunning = false
            }
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
