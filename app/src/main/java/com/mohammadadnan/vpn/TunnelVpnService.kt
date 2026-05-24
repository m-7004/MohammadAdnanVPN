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

    private fun extractXray(): File {
        val xrayFile = File(filesDir, "xray")
        if (!xrayFile.exists()) {
            assets.open("xray").use { input ->
                FileOutputStream(xrayFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        xrayFile.setExecutable(true)
        return xrayFile
    }

    private fun start() {
        val config = """
{
  "log": { "loglevel": "none" },
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true, "userLevel": 8 }
    },
    {
      "listen": "127.0.0.1",
      "port": 10809,
      "protocol": "http",
      "settings": { "userLevel": 8 }
    }
  ],
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [{
          "address": "51.254.130.47",
          "port": 80,
          "users": [{
            "id": "free.facebook.com",
            "encryption": "none",
            "flow": "",
            "level": 8
          }]
        }]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "none",
        "tcpSettings": {
          "header": {
            "type": "http",
            "request": {
              "version": "1.1",
              "method": "GET",
              "path": ["/"],
              "headers": {
                "Host": ["proxy.exhxx.com:8080"],
                "Connection": ["Keep-Alive"],
                "Proxy-Connection": ["Keep-Alive"]
              }
            }
          }
        }
      },
      "mux": { "enabled": true, "concurrency": 8 }
    }
  ]
}
        """.trimIndent()

        val configFile = File(filesDir, "config.json")
        configFile.writeText(config)

        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        thread {
            try {
                val xray = extractXray()
                xrayProcess = ProcessBuilder(xray.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                xrayProcess?.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
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
