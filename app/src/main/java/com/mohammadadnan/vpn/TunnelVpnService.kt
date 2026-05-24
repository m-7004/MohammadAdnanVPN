package com.mohammadadnan.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.*
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    private var vpnFd: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var running = false
    private val logFile by lazy { File("/sdcard/Download/vpn_log.txt") }

    private fun log(msg: String) {
        try { logFile.appendText("[${System.currentTimeMillis()}] $msg\n") } catch (e: Exception) {}
        android.util.Log.d("TunnelVPN", msg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        try { logFile.delete() } catch (e: Exception) {}
        thread {
            try { start() }
            catch (e: Exception) { log("CRASH: ${e.message}\n${e.stackTraceToString()}") }
        }
        return START_STICKY
    }

    private fun showNotification(server: String) {
        val channelId = "vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "VPN Status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Mohammad Adnan VPN")
            .setContentText("Connected • $server")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun extractBinary(name: String): File {
        // نجرب مجلدات مختلفة
        val dirs = listOf(
            File(applicationInfo.nativeLibraryDir),
            filesDir,
            cacheDir,
            File("/data/local/tmp")
        )
        for (dir in dirs) {
            try {
                val f = File(dir, name)
                assets.open(name).use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                f.setExecutable(true, false)
                // اختبر إذا يقدر يشتغل
                val test = Runtime.getRuntime().exec(arrayOf(f.absolutePath, "version"))
                test.waitFor()
                log("Binary works in: ${dir.absolutePath}")
                return f
            } catch (e: Exception) {
                log("Dir ${dir.absolutePath} failed: ${e.message}")
            }
        }
        throw Exception("Cannot find executable directory")
    }

    private fun start() {
        running = true
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!.toIntOrNull() ?: 80
        val uuid = prefs.getString("uuid", "free.facebook.com")!!
        val payload = prefs.getString("payload", "HTTP/78 2026")!!

        log("Starting VPN $server:$port")
        showNotification("$server:$port")

        // config xray
        val config = """
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "127.0.0.1", "port": 10808,
    "protocol": "socks",
    "settings": {"auth": "noauth", "udp": true}
  }],
  "outbounds": [{
    "protocol": "vless",
    "settings": {
      "vnext": [{"address": "$server", "port": $port,
        "users": [{"id": "$uuid", "encryption": "none"}]}]
    },
    "streamSettings": {
      "network": "tcp", "security": "none",
      "tcpSettings": {
        "header": {"type": "http",
          "request": {"version": "1.1", "method": "GET", "path": ["/"],
            "headers": {"Host": ["$server"], "Pragma": ["$payload"]}}}
      }
    }
  }]
}""".trimIndent()

        val configFile = File(cacheDir, "config.json")
        configFile.writeText(config)
        log("Config written")

        val xray = extractBinary("xray")
        log("Starting xray from ${xray.absolutePath}")

        xrayProcess = ProcessBuilder(xray.absolutePath, "run", "-c", configFile.absolutePath)
            .redirectErrorStream(true).start()

        thread {
            xrayProcess!!.inputStream.bufferedReader().forEachLine { log("xray: $it") }
            log("xray exit: ${try { xrayProcess?.exitValue() } catch(e:Exception){ "?" }}")
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

        if (vpnFd == null) { log("ERROR: vpnFd null"); return }
        log("VPN fd=${vpnFd!!.fd}")

        // tun2socks
        val tun2socks = extractBinary("tun2socks")
        log("Starting tun2socks")

        val t2s = ProcessBuilder(
            tun2socks.absolutePath,
            "-device", "fd://${vpnFd!!.fd}",
            "-proxy", "socks5://127.0.0.1:10808",
            "-loglevel", "info"
        ).redirectErrorStream(true).start()

        thread {
            t2s.inputStream.bufferedReader().forEachLine { log("t2s: $it") }
            log("t2s exit: ${try { t2s.exitValue() } catch(e:Exception){ "?" }}")
        }

        log("All started!")
    }

    private fun stop() {
        running = false
        xrayProcess?.destroy()
        xrayProcess = null
        vpnFd?.close()
        vpnFd = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
