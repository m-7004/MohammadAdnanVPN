package com.mohammadadnan.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private lateinit var tvServer: TextView
    private lateinit var tvUuid: TextView
    private lateinit var tvPayload: TextView
    private var isConnected = false
    private val VPN_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(16, 16, 16, 16)
        }

        // عنوان
        val tvTitle = TextView(this).apply {
            text = "Mohammad Adnan"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.END
            setPadding(8, 8, 8, 8)
        }

        // زر الإعدادات
        val btnSettings = Button(this).apply {
            text = "⚙ الإعدادات"
            textSize = 13f
            setTextColor(0xFF9B59B6.toInt())
            setBackgroundColor(0xFF000000.toInt())
            gravity = android.view.Gravity.END
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        // بطاقة الإعدادات
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding(16, 16, 16, 16)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = 12
            layoutParams = lp
        }

        fun addRow(label: String, valueView: TextView) {
            val div = android.view.View(this@MainActivity).apply {
                setBackgroundColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, 1).also {
                    it.topMargin = 10; it.bottomMargin = 10
                }
            }
            val lbl = TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                gravity = android.view.Gravity.END
            }
            card.addView(div)
            card.addView(lbl)
            card.addView(valueView)
        }

        val tvProtocol = TextView(this).apply {
            text = "VLess • TCP • Direct -> Target"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.END
        }
        card.addView(tvProtocol)

        tvServer = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.END
        }
        addRow("target server", tvServer)

        tvUuid = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.END
        }
        addRow("uuid", tvUuid)

        tvPayload = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.END
        }
        addRow("payload", tvPayload)

        btnConnect = Button(this).apply {
            text = "CONNECT"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9B59B6.toInt())
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = 24
            layoutParams = lp
        }
        btnConnect.setOnClickListener {
            if (!isConnected) startVpn() else stopVpn()
        }

        val tvVersion = TextView(this).apply {
            text = "Version 1.0 — Mohammad Adnan VPN"
            textSize = 11f
            setTextColor(0xFF444444.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        root.addView(tvTitle)
        root.addView(btnSettings)
        root.addView(card)
        root.addView(btnConnect)
        root.addView(tvVersion)

        setContentView(root)
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!
        tvServer.text = "$server:$port"
        tvUuid.text = prefs.getString("uuid", "free.facebook.com")
        val payload = prefs.getString("payload", "HTTP/78 2026")!!
        tvPayload.text = payload.lines().firstOrNull() ?: payload
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_CODE)
        else onActivityResult(VPN_CODE, Activity.RESULT_OK, null)
    }

    private fun stopVpn() {
        startService(Intent(this, TunnelVpnService::class.java).apply { action = "STOP" })
        isConnected = false
        btnConnect.text = "CONNECT"
        btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9B59B6.toInt())
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_CODE && resultCode == RESULT_OK) {
            startService(Intent(this, TunnelVpnService::class.java).apply { action = "START" })
            isConnected = true
            btnConnect.text = "DISCONNECT"
            btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC0392B.toInt())
        }
    }
}
