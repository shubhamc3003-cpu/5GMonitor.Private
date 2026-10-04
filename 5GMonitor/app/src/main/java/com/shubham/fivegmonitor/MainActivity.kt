package com.shubham.fivegmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var action: Button
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(44, 70, 44, 32)
            setBackgroundColor(0xFFF5F7F6.toInt())
        }
        val title = TextView(this).apply { text = "5G Monitor"; textSize = 28f; setTextColor(0xFF17211C.toInt()) }
        val intro = TextView(this).apply { text = "Know when your phone loses 5G"; textSize = 15f; setTextColor(0xFF68736D.toInt()); setPadding(0, 12, 0, 42) }
        status = TextView(this).apply { textSize = 34f; setTextColor(0xFF147D58.toInt()); gravity = Gravity.CENTER; text = "Not monitoring" }
        details = TextView(this).apply { textSize = 15f; setTextColor(0xFF68736D.toInt()); gravity = Gravity.CENTER; setPadding(0, 12, 0, 34) }
        action = Button(this).apply { textSize = 16f; setOnClickListener { toggleMonitoring() } }
        val alarmNote = TextView(this).apply {
            text = "When 5G is lost, the app plays a repeating alarm until 5G returns or you dismiss it. Alarm audibility depends on your phone's volume, Do Not Disturb and battery settings."
            textSize = 14f; setTextColor(0xFF68736D.toInt()); setPadding(0, 30, 0, 0)
        }
        val history = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF34423A.toInt()); setPadding(0, 28, 0, 0)
            text = "Last event: —"
            tag = "history"
        }
        root.addView(title)
        root.addView(intro)
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(alarmNote)
        root.addView(history)
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 40)
    }

    private fun toggleMonitoring() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow Phone permission to detect network changes", Toast.LENGTH_LONG).show()
            requestNeededPermissions(); return
        }
        val enabled = prefs.getBoolean("enabled", false)
        val intent = Intent(this, NetworkMonitorService::class.java).apply {
            action = if (enabled) NetworkMonitorService.ACTION_STOP else NetworkMonitorService.ACTION_START
        }
        if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        prefs.edit().putBoolean("enabled", !enabled).apply()
        refreshUi()
    }

    private fun refreshUi() {
        if (!::status.isInitialized) return
        val enabled = prefs.getBoolean("enabled", false)
        status.text = if (enabled) prefs.getString("network", "Checking network…") ?: "Checking network…" else "Not monitoring"
        status.setTextColor(if (enabled && status.text.toString().contains("5G")) 0xFF147D58.toInt() else 0xFF34423A.toInt())
        details.text = if (enabled) "Background monitoring is active" else "Turn monitoring on to receive 5G loss alerts"
        action.text = if (enabled) "Turn monitoring OFF" else "Start monitoring"
        val hist = findViewByIdFromRoot("history")
        hist?.text = "Last event: ${prefs.getString("last_event", "—")}"
    }

    private fun findViewByIdFromRoot(tagValue: String): TextView? {
        fun search(v: android.view.View): TextView? {
            if (v.tag == tagValue && v is TextView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) search(v.getChildAt(i))?.let { return it }
            return null
        }
        return search(window.decorView)
    }
}
