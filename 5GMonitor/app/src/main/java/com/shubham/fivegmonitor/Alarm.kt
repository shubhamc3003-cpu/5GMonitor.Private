package com.shubham.fivegmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Plays the alert: a spoken voice phrase or a sound/music file, repeated N times. */
object Alarm {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var voiceOk = false
    private var vib: Vibrator? = null
    private var savedVol = -1
    private var gen = 0
    private var remaining = 0
    private var ringNo = 0
    private var real = false
    private var active = false
    var onFinished: (() -> Unit)? = null
    val playing: Boolean get() = active

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private fun audio(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start(ctx: Context, isReal: Boolean = false) {
        stop(ctx)
        val c = ctx.applicationContext
        val p = prefs(c)
        active = true; real = isReal; ringNo = 0
        val g = ++gen
        savedVol = audio(c).getStreamVolume(AudioManager.STREAM_ALARM)
        val times = p.getInt("s_times", 3)
        remaining = if (times == 0) -1 else times
        if (p.getBoolean("s_vibrate", true)) vibrate(c)
        if (p.getInt("s_type", 0) == 0) {
            tts = TextToSpeech(c) { status ->
                val t = tts
                if (g == gen && active) {
                    if (status == TextToSpeech.SUCCESS && t != null) {
                        val r = t.setLanguage(Locale.getDefault())
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
                        t.setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        )
                        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { h.post { after(c, g) } }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) { h.post { after(c, g) } }
                        })
                        voiceOk = true
                        nextRing(c, g)
                    } else {
                        startMusic(c, g)
                    }
                }
            }
        } else {
            startMusic(c, g)
        }
    }

    private fun after(c: Context, g: Int) { h.postDelayed({ nextRing(c, g) }, 800) }

    private fun startMusic(c: Context, g: Int) {
        val custom = prefs(c).getString("s_sound", "") ?: ""
        val candidates = listOfNotNull(
            if (custom.isNotEmpty()) Uri.parse(custom) else null,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (u in candidates) { if (prepare(c, u, g)) break }
        nextRing(c, g)
    }

    private fun prepare(c: Context, uri: Uri, g: Int): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(c, uri)
            mp.setOnCompletionListener { h.post { after(c, g) } }
            mp.prepare()
            player = mp
            true
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) { }
            false
        }
    }

    private fun nextRing(c: Context, g: Int) {
        if (g != gen || !active) return
        if (remaining == 0) {
            val r = real
            stop(c)
            if (r) onFinished?.invoke()
            return
        }
        if (remaining > 0) remaining--
        ringNo++
        val p = prefs(c)
        val frac = if (p.getBoolean("s_escalate", false)) minOf(1f, 0.4f + 0.3f * (ringNo - 1)) else 1f
        try {
            val am = audio(c)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(1, (max * p.getInt("s_volume", 100) / 100f * frac).toInt()), 0)
        } catch (_: Exception) { }
        val t = tts
        val mp = player
        if (voiceOk && t != null) {
            t.speak(p.getString("s_phrase", "5G signal lost") ?: "5G signal lost", TextToSpeech.QUEUE_FLUSH, null, "alert")
        } else if (mp != null) {
            try { mp.start() } catch (_: Exception) { stop(c) }
        } else {
            stop(c)
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
        gen++
        active = false
        h.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null; voiceOk = false
        try { vib?.cancel() } catch (_: Exception) { }
        vib = null
        if (savedVol >= 0) {
            try { audio(ctx.applicationContext).setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0) } catch (_: Exception) { }
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
