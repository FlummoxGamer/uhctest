package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.harvester.companion.core.lifecycle.AppSleepManager

class SessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "uh_session_keepalive"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_UPDATE = "app.harvester.companion.ACTION_SESSION_UPDATE"
        const val ACTION_QUIT = "app.harvester.companion.ACTION_SESSION_QUIT"
        const val EXTRA_SLEEPING = "extra_sleeping"
        const val EXTRA_HAS_TABS = "extra_has_tabs"

        fun startOrUpdate(context: Context, isSleeping: Boolean, hasTabs: Boolean) {
            if (!hasTabs) {
                try {
                    context.stopService(Intent(context, SessionService::class.java))
                } catch (_: Exception) {}
                return
            }
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_SLEEPING, isSleeping)
                putExtra(EXTRA_HAS_TABS, true)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            AppSleepManager.persistNow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasTabs = intent?.getBooleanExtra(EXTRA_HAS_TABS, true) ?: true
        val isSleeping = intent?.getBooleanExtra(EXTRA_SLEEPING, false) ?: false

        if (!hasTabs) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(isSleeping)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            UHLog.w("lifecycle_warn", "Unable to elevate SessionService foreground state: ${e.message}")
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Browsing Session Protection",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps open tabs preserved in background without reloading"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(isSleeping: Boolean) = run {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val quitIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).apply { action = ACTION_QUIT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle(if (isSleeping) "Browser in sleep mode" else "Browsing session active")
            .setContentText(
                if (isSleeping) "Low-power state • Tap to resume instantly"
                else "Tabs kept active in background"
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent)
            .addAction(0, "Close", quitIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppSleepManager.persistNow()
        try {
            if (SessionRecorder.isActive()) SessionRecorder.save(this)
        } catch (_: Exception) {}
        try { SessionRecorder.stop() } catch (_: Exception) {}
        UHLog.markCleanExit(this)
        UHLog.endSession(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
