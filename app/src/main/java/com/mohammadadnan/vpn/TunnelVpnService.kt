package com.mohammadadnan.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    private var vpnFd: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        start()
        return START_STICKY
    }

    private fun start() {
        // كتابة config لـ Xray
        val config = """
{
  "inbounds": [{
    "port": 10808,
    "protocol": "socks",
    "settings": { "auth": "noauth", "udp": true }
  }],
  "outbounds": [{
    "protocol": "vless",
    "settings": {
      "vnext": [{
        "address": "51.254.130.47",
        "port": 80,
        "users": [{
          "id": "free.facebook.com",
          "encryption": "none"
        }]
      }]
    },
    "streamSettings": {
      "network": "tcp",
      "tcpSettings": {
        "header": {
          "type": "http",
          "request": {
            "version": "1.1",
            "method": "GET",
            "path": ["/"],
            "headers": {
              "Host": ["free.facebook.com"],
              "Upgrade": ["websocket"],
              "X-Payload": ["HTTP/78 2026"]
            }
          }
        }
      }
    }
  }]
}
        """.trimIndent()

        val configFile = File(filesDir, "xray_config.json")
        configFile.writeText(config)

        // تشغيل VPN interface
        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        // تشغيل Xray
        val xrayFile = File(filesDir, "xray")
        if (xrayFile.exists()) {
            xrayFile.setExecutable(true)
            thread {
                xrayProcess = ProcessBuilder(xrayFile.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            }
        }
    }

    private fun stop() {
        xrayProcess?.destroy()
        xrayProcess = null
        vpnFd?.close()
        vpnFd = null
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
