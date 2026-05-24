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
    private var tun2socksProcess: Process? = null
    private var running = false
    private val logFile by lazy {
        File("/sdcard/Download/vpn_log.txt")
    }

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
        val f = File(filesDir, name)
        assets.open(name).use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        f.setExecutable(true)
        log("Extracted $name: ${f.length()} bytes")
        return f
    }

    private fun start() {
        running = true
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!.toIntOrNull() ?: 80

        log("Starting VPN $server:$port")
        showNotification("$server:$port")

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

        val tun2socks = extractBinary("tun2socks")
        log("Starting tun2socks...")

        tun2socksProcess = ProcessBuilder(
            tun2socks.absolutePath,
            "-device", "fd://${vpnFd!!.fd}",
            "-proxy", "socks5://127.0.0.1:10808",
            "-loglevel", "info"
        ).redirectErrorStream(true).start()

        thread {
            tun2socksProcess!!.inputStream.bufferedReader().forEachLine { log("t2s: $it") }
            log("tun2socks exit: ${try { tun2socksProcess?.exitValue() } catch(e:Exception){ "running" }}")
        }
        log("Done")
    }

    private fun stop() {
        running = false
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        vpnFd?.close()
        vpnFd = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
