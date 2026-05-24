package com.mohammadadnan.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    private var vpnFd: ParcelFileDescriptor? = null
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

    private fun pipe(src: InputStream, dst: OutputStream) {
        try {
            val buf = ByteArray(8192)
            while (running) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (e: Exception) {}
    }

    private fun connectToServer(server: String, port: Int, payload: String): Socket? {
        return try {
            val sock = Socket()
            protect(sock)
            sock.connect(InetSocketAddress(server, port), 10000)
            sock.soTimeout = 0

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // إرسال البايلود
            out.write((payload + "\r\n\r\n").toByteArray())
            out.flush()
            log("Payload sent")

            // قراءة رد 200 OK
            val buf = ByteArray(1024)
            val n = inp.read(buf)
            val response = String(buf, 0, n)
            log("Server response: $response")

            if (response.contains("200")) {
                log("Tunnel established!")
                sock
            } else {
                log("Bad response, closing")
                sock.close()
                null
            }
        } catch (e: Exception) {
            log("Connect error: ${e.message}")
            null
        }
    }

    private fun start() {
        running = true
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!.toIntOrNull() ?: 80
        val payload = prefs.getString("payload", "HTTP/78 2026")!!

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

        val sock = connectToServer(server, port, payload)
        if (sock == null) { log("Failed to connect"); return }

        val vpnIn = FileInputStream(vpnFd!!.fileDescriptor)
        val vpnOut = FileOutputStream(vpnFd!!.fileDescriptor)
        val sockIn = sock.getInputStream()
        val sockOut = sock.getOutputStream()

        log("Starting tunnel pipes")

        // VPN → Server
        thread { pipe(vpnIn, sockOut) }
        // Server → VPN
        thread { pipe(sockIn, vpnOut) }

        log("Tunnel running")
    }

    private fun stop() {
        running = false
        vpnFd?.close()
        vpnFd = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
