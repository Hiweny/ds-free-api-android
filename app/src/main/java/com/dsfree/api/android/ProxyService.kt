package com.dsfree.api.android

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ProxyService : Service() {

    companion object {
        private const val TAG = "DSFreeAPI"
        private const val BINARY_NAME = "ds-free-api"
        private const val CONFIG_NAME = "config.toml"
    }

    private var proxyProcess: java.lang.Process? = null
    private lateinit var notificationHelper: NotificationHelper
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_PAUSE -> pauseProxy()
            NotificationHelper.ACTION_RESUME -> resumeProxy()
            NotificationHelper.ACTION_STOP -> stopProxy()
            else -> startProxy()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        if (isRunning) return

        // 显示前台通知
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.createRunningNotification()
        )

        serviceScope.launch {
            try {
                // 1. 准备二进制文件
                val binaryFile = prepareBinary()
                // 2. 准备配置文件
                prepareConfig()
                // 3. 启动二进制
                launchBinary(binaryFile)
                isRunning = true
                Log.i(TAG, "ds-free-api 代理服务已启动")
            } catch (e: Exception) {
                Log.e(TAG, "启动代理服务失败", e)
                stopSelf()
            }
        }
    }

    private fun prepareBinary(): File {
        val binaryFile = File(filesDir, BINARY_NAME)

        // 如果二进制已存在且可执行，直接复用
        if (binaryFile.exists() && binaryFile.canExecute()) {
            Log.d(TAG, "二进制文件已存在: ${binaryFile.absolutePath}")
            return binaryFile
        }

        // 从 assets 拷贝二进制
        try {
            val inputStream = assets.open(BINARY_NAME)
            FileOutputStream(binaryFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // 设置可执行权限
            binaryFile.setExecutable(true, false)
            binaryFile.setReadable(true, false)
            binaryFile.setWritable(false, false)

            Log.i(TAG, "二进制文件已安装: ${binaryFile.absolutePath} (${binaryFile.length()} bytes)")
        } catch (e: IOException) {
            throw RuntimeException("无法从 assets 提取二进制文件: ${e.message}", e)
        }

        return binaryFile
    }

    private fun prepareConfig() {
        val configFile = File(filesDir, CONFIG_NAME)

        // 如果配置文件已存在，不覆盖
        if (configFile.exists()) {
            Log.d(TAG, "配置文件已存在: ${configFile.absolutePath}")
            return
        }

        try {
            val inputStream = assets.open("config.example.toml")
            FileOutputStream(configFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            configFile.setReadable(true, false)
            configFile.setWritable(true, false)
            Log.i(TAG, "默认配置文件已创建: ${configFile.absolutePath}")
        } catch (e: IOException) {
            // 如果 assets 中也没有，创建一个最小配置
            configFile.writeText("""
[server]
host = "127.0.0.1"
port = 22217

[ds_core]
            """.trimIndent())
            Log.w(TAG, "创建了最小配置文件: ${configFile.absolutePath}")
        }
    }

    private fun launchBinary(binaryFile: File) {
        val configFile = File(filesDir, CONFIG_NAME)
        val dataDir = filesDir.absolutePath

        val command = arrayOf(
            binaryFile.absolutePath,
            "--config", configFile.absolutePath
        )

        val processBuilder = ProcessBuilder(*command)
        processBuilder.directory(filesDir)
        processBuilder.environment()["DS_DATA_DIR"] = dataDir
        processBuilder.environment()["HOME"] = filesDir.absolutePath
        processBuilder.redirectErrorStream(true)

        try {
            proxyProcess = processBuilder.start()

            // 读取进程输出用于日志
            serviceScope.launch {
                try {
                    proxyProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "[ds-free-api] $line")
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
                    // 如果服务还在运行，尝试重启
                    if (exitCode != 0) {
                        delay(3000)
                        if (!isRunning) {
                            Log.i(TAG, "尝试重启 ds-free-api...")
                            launchBinary(binaryFile)
                        }
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