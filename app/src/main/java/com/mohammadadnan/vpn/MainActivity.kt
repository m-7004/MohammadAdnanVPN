package com.mohammadadnan.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private var isConnected = false
    private val VPN_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnConnect = findViewById(R.id.btnConnect)
        btnConnect.setOnClickListener {
            if (!isConnected) startVpn() else stopVpn()
        }
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
        btnConnect.backgroundTintList = getColorStateList(R.color.purple_connect)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_CODE && resultCode == RESULT_OK) {
            startService(Intent(this, TunnelVpnService::class.java).apply { action = "START" })
            isConnected = true
            btnConnect.text = "DISCONNECT"
            btnConnect.backgroundTintList = getColorStateList(R.color.red_disconnect)
        }
    }
}
