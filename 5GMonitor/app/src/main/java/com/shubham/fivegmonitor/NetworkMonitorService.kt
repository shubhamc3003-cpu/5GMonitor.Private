package com.shubham.fivegmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.AudioManager
import android.net.Uri
import android.widget.Toast

class NetworkMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.shubham.fivegmonitor.START"
        const val ACTION_STOP = "com.shubham.fivegmonitor.STOP"
        const val ACTION_DISMISS = "com.shubham.fivegmonitor.DISMISS"
        private const val CHANNEL = "network_monitor"
        private const val NOTIFICATION_ID = 5105
    }

    private lateinit var telephony: TelephonyManager
    private var mediaPlayer: MediaPlayer? = null
    private var was5g: Boolean? = null
    private var modernCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_DISMISS -> {
                stopAlarm()
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification("Alarm dismissed · Monitoring mobile network"))
                return START_STICKY
            }
            ACTION_START, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        // Avoid duplicate telephony listener registration after repeated start intents.
        if (modernCallback != null || legacyListener != null) {
            startForeground(NOTIFICATION_ID, buildNotification("Monitoring mobile network"))
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring mobile network"))
        prefs.edit().putBoolean("enabled", true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    val is5g = info.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                    onNetworkState(is5g, if (is5g) "5G" else networkLabel(info.networkType))
                }
            }
            modernCallback = callback
            try { telephony.registerTelephonyCallback(mainExecutor, callback) } catch (_: SecurityException) { stopMonitoring() }
        } else {
            val listener = object : PhoneStateListener() {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    val is5g = info.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                    onNetworkState(is5g, if (is5g) "5G" else networkLabel(info.networkType))
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            try { telephony.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED) } catch (_: SecurityException) { stopMonitoring() }
        }
        // Initial state is reported by the telephony display callback; do not assume the device is on 5G.
    }

    private fun onNetworkState(is5g: Boolean, label: String) {
        val previous = was5g
        was5g = is5g
        prefs.edit().putString("network", label).apply()
        if (previous == true && !is5g) {
            prefs.edit().putString("last_event", "5G lost at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}").apply()
            startAlarm()
        } else if (is5g && previous == false) {
            prefs.edit().putString("last_event", "5G restored at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}").apply()
            stopAlarm()
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(if (is5g) "5G connected" else "Network: $label"))
    }

    private fun networkLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> "Mobile network changed"
    }

    private fun startAlarm() {
        if (mediaPlayer?.isPlaying == true) return
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@NetworkMonitorService, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "5GMonitor:Alarm").apply { acquire(10 * 60 * 1000L) }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification("5G lost! Alarm is ringing"))
        } catch (_: Exception) { stopAlarm() }
    }

    private fun stopAlarm() {
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        mediaPlayer?.release(); mediaPlayer = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modernCallback?.let { try { telephony.unregisterTelephonyCallback(it) } catch (_: Exception) { } }
        else legacyListener?.let { @Suppress("DEPRECATION") telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
        modernCallback = null; legacyListener = null
        stopAlarm()
        prefs.edit().putBoolean("enabled", false).putString("network", "Not monitoring").apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, 2, Intent(this, NetworkMonitorService::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("5G Monitor").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss alarm", dismiss).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "5G monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing status for 5G network monitoring"
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() { stopAlarm(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
