package com.mohammadadnan.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

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
        assets.open(name).use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        f.setExecutable(true)
        return f
    }

    private fun start() {
        running = true

        val config = """
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "127.0.0.1",
    "port": 10808,
    "protocol": "socks",
    "settings": {"auth": "noauth", "udp": true}
  }],
  "outbounds": [{
    "protocol": "vless",
    "settings": {
      "vnext": [{
        "address": "51.254.130.47",
        "port": 80,
        "users": [{"id": "free.facebook.com", "encryption": "none", "level": 8}]
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
    "mux": {"enabled": true, "concurrency": 8}
  }]
}""".trimIndent()

        val configFile = File(filesDir, "config.json")
        configFile.writeText(config)

        // شغّل Xray
        thread {
            try {
                val xray = extractBinary("xray")
                xrayProcess = ProcessBuilder(xray.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) { e.printStackTrace() }
        }

        // انتظر حتى Xray يشتغل
        Thread.sleep(2000)

        // فتح VPN interface
        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        // تمرير الترافيك عبر SOCKS
        thread { forwardTraffic() }
    }

    private fun forwardTraffic() {
        val buf = ByteBuffer.allocate(32767)
        val vpn = vpnFd ?: return
        val input = java.io.FileInputStream(vpn.fileDescriptor)
        val output = java.io.FileOutputStream(vpn.fileDescriptor)

        try {
            val tunnel = DatagramChannel.open()
            protect(tunnel.socket())
            tunnel.connect(InetSocketAddress("127.0.0.1", 10808))

            while (running) {
                buf.clear()
                val len = input.read(buf.array())
                if (len > 0) {
                    buf.limit(len)
                    tunnel.write(buf)
                    buf.clear()
                    val rlen = tunnel.read(buf)
                    if (rlen > 0) output.write(buf.array(), 0, rlen)
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
