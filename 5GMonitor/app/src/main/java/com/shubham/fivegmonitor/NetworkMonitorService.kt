package com.shubham.fivegmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plays the alarm. Shared by the service and the "Test alarm" button. */
object Alarm {
    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null
    private var savedVol = -1
    val playing: Boolean get() = player != null

    fun start(ctx: Context) {
        stop(ctx)
        val c = ctx.applicationContext
        val p = c.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(1, max * p.getInt("s_volume", 100) / 100), 0)
        } catch (_: Exception) { }
        val custom = p.getString("s_sound", "") ?: ""
        val candidates = listOfNotNull(
            if (custom.isNotEmpty()) Uri.parse(custom) else null,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (u in candidates) { if (play(c, u)) break }
        if (p.getBoolean("s_vibrate", true)) vibrate(c)
    }

    private fun play(c: Context, uri: Uri): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(c, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) { }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(c: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        } catch (_: Exception) { }
    }

    fun stop(ctx: Context) {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { vib?.cancel() } catch (_: Exception) { }
        vib = null
        if (savedVol >= 0) {
            try {
                (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0)
            } catch (_: Exception) { }
            savedVol = -1
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = c.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        if (i.action == Intent.ACTION_BOOT_COMPLETED && p.getBoolean("s_boot", false)) {
            try {
                c.startForegroundService(
                    Intent(c, NetworkMonitorService::class.java).setAction(NetworkMonitorService.ACTION_START)
                )
            } catch (_: Exception) { }
        }
    }
}

class NetworkMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.shubham.fivegmonitor.START"
        const val ACTION_STOP = "com.shubham.fivegmonitor.STOP"
        const val ACTION_DISMISS = "com.shubham.fivegmonitor.DISMISS"
        private const val CH_STATUS = "network_monitor"
        private const val CH_ALERT = "network_alert"
        private const val ID_STATUS = 5105
        private const val ID_ALERT = 5106
    }

    private lateinit var tm: TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private val p by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private val nm get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private var callback: TelephonyCallback? = null
    private var current: Boolean? = null
    private var seen5g = false
    private var dismissed = false
    private var alarmOn = false
    private var label = "Checking…"

    override fun onCreate() {
        super.onCreate()
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_DISMISS -> {
                dismissed = true
                handler.removeCallbacksAndMessages(null)
                stopAlarm()
                refresh()
                return START_STICKY
            }
            else -> {
                if (intent == null && !p.getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY }
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        startForeground(ID_STATUS, notif(CH_STATUS, statusText(), false))
        if (callback != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Android 12 or newer is required", Toast.LENGTH_LONG).show()
            stopMonitoring(); return
        }
        p.edit().putBoolean("enabled", true).putBoolean("is5g", false).putString("network", "Checking…").apply()
        current = null; seen5g = false; dismissed = false
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                val g = info.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                onState(g, if (g) "5G" else typeLabel(info.networkType))
            }
        }
        callback = cb
        try { tm.registerTelephonyCallback(mainExecutor, cb) } catch (e: SecurityException) { stopMonitoring() }
    }

    private fun onState(is5g: Boolean, newLabel: String) {
        val prev = current
        current = is5g
        label = newLabel
        p.edit().putString("network", newLabel).putBoolean("is5g", is5g).apply()
        handler.removeCallbacksAndMessages(null)
        if (prev != is5g) {
            addLog(if (is5g) "5G connected" else if (prev == null) "Started on $newLabel" else "5G lost → $newLabel")
        }
        if (is5g) {
            seen5g = true
            dismissed = false
            if (alarmOn && p.getBoolean("s_autostop", true)) stopAlarm()
        } else if (!alarmOn && !dismissed && (p.getInt("s_mode", 1) == 1 || seen5g)) {
            val delayMs = p.getInt("s_delay", 0) * 1000L
            handler.postDelayed({ if (current == false) raiseAlarm() }, delayMs)
        }
        refresh()
    }

    private fun raiseAlarm() {
        alarmOn = true
        Alarm.start(this)
        addLog("Alarm started")
        nm.notify(ID_ALERT, notif(CH_ALERT, "Not on 5G — now on $label", true))
        refresh()
    }

    private fun stopAlarm() {
        alarmOn = false
        Alarm.stop(this)
        nm.cancel(ID_ALERT)
    }

    private fun addLog(msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val old = (p.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
        p.edit().putString("log", (listOf("$t  $msg") + old).take(15).joinToString("\n")).apply()
    }

    private fun typeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "No data network"
        else -> "Mobile network"
    }

    private fun statusText(): String =
        if (alarmOn) "Not on 5G! Alarm is ringing" else if (current == true) "5G connected" else "Network: $label"

    private fun refresh() { nm.notify(ID_STATUS, notif(CH_STATUS, statusText(), false)) }

    private fun notif(channel: String, text: String, alert: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val b = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (alert) "5G lost!" else "5G Monitor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(!alert)
        if (alarmOn || alert) {
            val dismiss = PendingIntent.getService(
                this, 2, Intent(this, NetworkMonitorService::class.java).setAction(ACTION_DISMISS), flags
            )
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss alarm", dismiss)
        }
        return b.build()
    }

    private fun createChannels() {
        val status = NotificationChannel(CH_STATUS, "5G monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing status for 5G monitoring"
            setSound(null, null); enableVibration(false)
        }
        val alert = NotificationChannel(CH_ALERT, "5G lost alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while the 5G-lost alarm is ringing"
            setSound(null, null); enableVibration(false)
        }
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(alert)
    }

    private fun stopMonitoring() {
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback?.let { try { tm.unregisterTelephonyCallback(it) } catch (_: Exception) { } }
        }
        callback = null
        stopAlarm()
        p.edit().putBoolean("enabled", false).putBoolean("is5g", false).putString("network", "Off").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAlarm(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
