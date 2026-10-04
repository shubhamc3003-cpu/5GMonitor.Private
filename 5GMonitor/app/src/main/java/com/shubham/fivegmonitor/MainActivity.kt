package com.shubham.fivegmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private val BG = 0xFFF5F7F6.toInt()
private val INK = 0xFF17211C.toInt()
private val MUTED = 0xFF68736D.toInt()
private val GREEN = 0xFF147D58.toInt()
private val ORANGE = 0xFFC2410C.toInt()
private val GREY = 0xFF6B7280.toInt()
private val RED = 0xFFB91C1C.toInt()
private val DARK = 0xFF34423A.toInt()

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusCard: LinearLayout
    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var actionBtn: Button
    private lateinit var testBtn: Button
    private lateinit var soundBtn: Button
    private lateinit var logView: TextView
    private var testing = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refreshUi()
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (testing) Alarm.stop(this)
        super.onDestroy()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); isAllCaps = false; textSize = 16f
        background = bg(color, 16); setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = bg(Color.WHITE, 18)
    }

    private fun sw(t: String, key: String, def: Boolean) = Switch(this).apply {
        text = t; textSize = 15f; isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
    }

    private fun slider(max: Int, init: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = init
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = onChange(v)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** Adds a labelled slider to a card. valueText turns the raw progress into the label. */
    private fun LinearLayout.addSlider(key: String, def: Int, max: Int, offset: Int, step: Int, valueText: (Int) -> String) {
        val l = label("", 14f, INK)
        val start = (prefs.getInt(key, def) - offset) / step
        l.text = valueText(start * step + offset)
        addView(l, lp(14))
        addView(slider(max, start) { v ->
            val real = v * step + offset
            l.text = valueText(real)
            prefs.edit().putInt(key, real).apply()
        })
    }

    private fun hourName(h: Int) = String.format("%02d:00", h)

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); addView(root) }

        root.addView(label("5G Monitor", 26f, INK, true))
        root.addView(label("Get an alarm when your phone drops off 5G", 14f, MUTED), lp(2))

        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(30), dp(20), dp(30))
        }
        big = label("OFF", 44f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        sub = label("", 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        statusCard.addView(big)
        statusCard.addView(sub)
        root.addView(statusCard, lp(20))

        actionBtn = button("Start monitoring", GREEN) { toggleMonitoring() }
        root.addView(actionBtn, lp(14))
        testBtn = button("Test alarm", DARK) { toggleTest() }
        root.addView(testBtn, lp(8))

        // ----- when to alert -----
        root.addView(label("When to alert", 17f, INK, true), lp(26))
        val a = card()
        val rg = RadioGroup(this)
        rg.addView(RadioButton(this).apply { text = "Whenever I'm not on 5G"; id = 1 })
        rg.addView(RadioButton(this).apply { text = "Only after 5G was connected, then lost"; id = 2 })
        rg.check(if (prefs.getInt("s_mode", 1) == 1) 1 else 2)
        rg.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("s_mode", if (id == 1) 1 else 0).apply() }
        a.addView(rg)
        a.addSlider("s_delay", 0, 12, 0, 5) { if (it == 0) "Delay before alarm: none" else "Delay before alarm: $it s" }
        a.addSlider("s_snooze", 10, 29, 1, 1) { "Snooze length: $it min" }
        a.addView(sw("Stop alarm when 5G returns", "s_autostop", true), lp(14))
        a.addView(sw("Quiet hours (no alarm)", "s_quiet", false), lp(8))
        a.addSlider("s_qs", 23, 23, 0, 1) { "Quiet from ${hourName(it)}" }
        a.addSlider("s_qe", 7, 23, 0, 1) { "Quiet until ${hourName(it)}" }
        root.addView(a, lp(8))

        // ----- how it sounds -----
        root.addView(label("How it sounds", 17f, INK, true), lp(26))
        val s = card()
        val tg = RadioGroup(this)
        tg.addView(RadioButton(this).apply { text = "Voice (speaks a phrase)"; id = 1 })
        tg.addView(RadioButton(this).apply { text = "Music / alarm sound"; id = 2 })
        tg.check(if (prefs.getInt("s_type", 0) == 0) 1 else 2)
        tg.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("s_type", if (id == 1) 0 else 1).apply() }
        s.addView(tg)
        s.addView(label("Voice phrase", 13f, MUTED), lp(10))
        val phrase = EditText(this).apply {
            setText(prefs.getString("s_phrase", "5G signal lost"))
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) {
                    val t = e?.toString()?.trim().orEmpty()
                    prefs.edit().putString("s_phrase", if (t.isEmpty()) "5G signal lost" else t).apply()
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        s.addView(phrase)
        s.addSlider("s_times", 3, 10, 0, 1) { if (it == 0) "Rings: until dismissed" else "Rings: $it time" + (if (it > 1) "s" else "") }
        s.addSlider("s_volume", 100, 90, 10, 1) { "Alarm volume: $it%" }
        s.addView(sw("Get louder with each ring", "s_escalate", false), lp(12))
        s.addView(sw("Vibrate with alarm", "s_vibrate", true), lp(8))
        root.addView(s, lp(8))

        soundBtn = button("", DARK) { pickSound() }
        root.addView(soundBtn, lp(12))

        // ----- system -----
        root.addView(label("System", 17f, INK, true), lp(26))
        val y = card()
        y.addView(sw("Start monitoring after phone restarts", "s_boot", false))
        root.addView(y, lp(8))
        root.addView(button("Battery settings (recommended)", DARK) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) { Toast.makeText(this, "Open Settings > Apps > 5G Monitor > Battery", Toast.LENGTH_LONG).show() }
        }, lp(8))

        // ----- history -----
        root.addView(label("Recent events", 17f, INK, true), lp(26))
        val h = card()
        logView = label("", 13f, DARK)
        h.addView(logView)
        root.addView(h, lp(8))
        root.addView(
            label("Set battery to Unrestricted so OxygenOS doesn't stop monitoring. Alarm loudness also depends on Do Not Disturb.", 12f, MUTED),
            lp(16)
        )
        setContentView(scroll)
        updateSoundLabel()
    }

    private fun refreshUi() {
        if (!::big.isInitialized) return
        val on = prefs.getBoolean("enabled", false)
        val is5g = prefs.getBoolean("is5g", false)
        val net = prefs.getString("network", "") ?: ""
        statusCard.background = bg(if (!on) GREY else if (is5g) GREEN else ORANGE, 28)
        big.text = if (!on) "OFF" else if (net.isEmpty()) "…" else net
        sub.text = if (!on) "Monitoring is off" else if (is5g) "Connected to 5G" else "Not on 5G"
        actionBtn.text = if (on) "Stop monitoring" else "Start monitoring"
        actionBtn.background = bg(if (on) RED else GREEN, 16)
        val log = prefs.getString("log", "") ?: ""
        logView.text = if (log.isBlank()) "No events yet" else log
    }

    // ---------- actions ----------
    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 40)
    }

    private fun toggleMonitoring() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow Phone permission to detect network changes", Toast.LENGTH_LONG).show()
            requestNeededPermissions(); return
        }
        val on = prefs.getBoolean("enabled", false)
        val i = Intent(this, NetworkMonitorService::class.java)
            .setAction(if (on) NetworkMonitorService.ACTION_STOP else NetworkMonitorService.ACTION_START)
        if (on) startService(i) else startForegroundService(i)
    }

    private fun toggleTest() {
        if (testing) { stopTest(); return }
        testing = true
        Alarm.start(this)
        testBtn.text = "Stop test"
        handler.postDelayed({ stopTest() }, 12000)
    }

    private fun stopTest() {
        if (testing) Alarm.stop(this)
        testing = false
        handler.removeCallbacksAndMessages(null)
        testBtn.text = "Test alarm"
    }

    private fun pickSound() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            val cur = prefs.getString("s_sound", "") ?: ""
            if (cur.isNotEmpty()) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur))
        }
        startActivityForResult(i, 77)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val u = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString("s_sound", u?.toString() ?: "").apply()
            updateSoundLabel()
        }
    }

    private fun updateSoundLabel() {
        val s = prefs.getString("s_sound", "") ?: ""
        val name = if (s.isEmpty()) "Default alarm"
        else (try { RingtoneManager.getRingtone(this, Uri.parse(s))?.getTitle(this) } catch (e: Exception) { null } ?: "Custom")
        soundBtn.text = "Music for 'Music' mode: $name"
    }
}
