package com.mohammadadnan.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    companion object {
        const val SERVER = "51.254.130.47"
        const val PORT = 80
        const val PROXY_HOST = "proxy.exhxx.com"
        const val PROXY_PORT = 8080
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        start()
        return START_STICKY
    }

    private fun extractBinary(name: String): File {
        val f = File(filesDir, name)
        if (!f.exists() || f.length() < 1000) {
            assets.open(name).use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        }
        f.setExecutable(true)
        return f
    }

    private fun start() {
        running = true

        val config = """
{
  "log": {"loglevel": "warning"},
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": {"auth": "noauth", "udp": true, "userLevel": 8}
    },
    {
      "listen": "127.0.0.1", 
      "port": 10809,
      "protocol": "http",
      "settings": {"userLevel": 8}
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [{
          "address": "$SERVER",
          "port": $PORT,
          "users": [{
            "id": "free.facebook.com",
            "encryption": "none",
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
                "Host": ["$PROXY_HOST:$PROXY_PORT"],
                "Connection": ["Keep-Alive"],
                "Proxy-Connection": ["Keep-Alive"],
                "X-Custom-Header": ["HTTP/78 2026"]
              }
            }
          }
        }
      },
      "mux": {"enabled": true, "concurrency": 8}
    },
    {
      "tag": "direct",
      "protocol": "freedom",
      "settings": {}
    }
  ],
  "routing": {
    "rules": [
      {
        "type": "field",
        "ip": ["127.0.0.1"],
        "outboundTag": "direct"
      }
    ]
  }
}""".trimIndent()

        val configFile = File(filesDir, "config.json")
        configFile.writeText(config)

        thread {
            try {
                val xray = extractBinary("xray")
                xrayProcess = ProcessBuilder(xray.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) { e.printStackTrace() }
        }

        Thread.sleep(2000)

        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        thread { forwardViaSocks() }
    }

    private fun forwardViaSocks() {
        val vpn = vpnFd ?: return
        val vpnIn = FileInputStream(vpn.fileDescriptor)
        val vpnOut = FileOutputStream(vpn.fileDescriptor)
        val buf = ByteArray(4096)

        try {
            val sock = Socket()
            protect(sock)
            sock.connect(InetSocketAddress("127.0.0.1", 10808), 5000)

            val sockIn = sock.getInputStream()
            val sockOut = sock.getOutputStream()

            thread {
                try {
                    while (running) {
                        val len = sockIn.read(buf)
                        if (len > 0) vpnOut.write(buf, 0, len)
                    }
                } catch (e: Exception) {}
            }

            while (running) {
                val len = vpnIn.read(buf)
                if (len > 0) {
                    sockOut.write(buf, 0, len)
                    sockOut.flush()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stop() {
        running = false
        xrayProcess?.destroy()
        xrayProcess = null
        vpnFd?.close()
        vpnFd = null
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
