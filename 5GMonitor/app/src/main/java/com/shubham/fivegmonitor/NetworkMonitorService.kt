package com.shubham.fivegmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NetworkMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.shubham.fivegmonitor.START"
        const val ACTION_STOP = "com.shubham.fivegmonitor.STOP"
        const val ACTION_DISMISS = "com.shubham.fivegmonitor.DISMISS"
        const val ACTION_SNOOZE = "com.shubham.fivegmonitor.SNOOZE"
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
    private var snoozeUntil = 0L
    private var noSvc = false
    private var lastG = false
    private var lastLabel = "Checking…"

    override fun onCreate() {
        super.onCreate()
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createChannels()
        Alarm.onFinished = {
            alarmOn = false; dismissed = true
            nm.cancel(ID_ALERT)
            addLog("Alarm finished")
            refresh()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_DISMISS -> {
                dismissed = true
                handler.removeCallbacksAndMessages(null)
                stopAlarm(); refresh()
                return START_STICKY
            }
            ACTION_SNOOZE -> {
                val min = p.getInt("s_snooze", 10)
                snoozeUntil = System.currentTimeMillis() + min * 60000L
                dismissed = false
                stopAlarm()
                addLog("Snoozed for $min min")
                maybeAlert(); refresh()
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
        current = null; seen5g = false; dismissed = false; noSvc = false; snoozeUntil = 0L
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener, TelephonyCallback.ServiceStateListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                lastG = info.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                lastLabel = if (lastG) "5G" else typeLabel(info.networkType)
                if (!noSvc) onState(lastG, lastLabel)
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                val out = serviceState.state != ServiceState.STATE_IN_SERVICE
                if (out != noSvc) {
                    noSvc = out
                    if (out) onState(false, "No service") else onState(lastG, lastLabel)
                }
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
        if (prev != is5g) {
            addLog(if (is5g) "5G connected" else if (prev == null) "Started on $newLabel" else "5G lost → $newLabel")
        }
        if (is5g) {
            seen5g = true; dismissed = false; snoozeUntil = 0L
            if (alarmOn && p.getBoolean("s_autostop", true)) stopAlarm()
        }
        maybeAlert()
        refresh()
    }

    private fun inQuietHours(): Boolean {
        if (!p.getBoolean("s_quiet", false)) return false
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = p.getInt("s_qs", 23)
        val e = p.getInt("s_qe", 7)
        return if (s == e) false else if (s < e) hr in s until e else (hr >= s || hr < e)
    }

    private fun maybeAlert() {
        handler.removeCallbacksAndMessages(null)
        if (current != false || alarmOn || dismissed || inQuietHours()) return
        if (!(p.getInt("s_mode", 1) == 1 || seen5g)) return
        val d = maxOf(p.getInt("s_delay", 0) * 1000L, snoozeUntil - System.currentTimeMillis())
        handler.postDelayed({ if (current == false && !alarmOn) raiseAlarm() }, d)
    }

    private fun raiseAlarm() {
        alarmOn = true
        Alarm.start(this, true)
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
        p.edit().putString("log", (listOf("$t  $msg") + old).take(20).joinToString("\n")).apply()
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
            val snooze = PendingIntent.getService(
                this, 3, Intent(this, NetworkMonitorService::class.java).setAction(ACTION_SNOOZE), flags
            )
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismiss)
            b.addAction(android.R.drawable.ic_menu_recent_history, "Snooze", snooze)
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

    override fun onDestroy() {
        Alarm.onFinished = null
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
