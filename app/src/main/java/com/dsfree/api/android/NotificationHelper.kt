package com.dsfree.api.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ds_free_api_service"
        const val CHANNEL_NAME = "代理服务"
        const val NOTIFICATION_ID = 1001
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DeepSeek API 代理服务运行状态"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createRunningNotification(): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            context, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DeepSeek API 代理")
            .setContentText("代理服务运行中 · 端口 22217")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    fun createPausedNotification(): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_RESUME
        }
        val resumePendingIntent = PendingIntent.getService(
            context, 3, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("DeepSeek API 代理")
            .setContentText("代理服务已暂停")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "恢复", resumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }
}
