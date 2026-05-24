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
        const val PAYLOAD = "HTTP/78 2026 HTTP/1.1 300 ok\r\nHost: proxy.exhxx.com:8080\r\nConnection: Keep-Alive\r\nProxy-Connection: Keep-Alive\r\n\r\n"
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var running = false
    private var socket: Socket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        start()
        return START_STICKY
    }

    private fun start() {
        running = true

        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        thread { tunnel() }
    }

    private fun tunnel() {
        try {
            val sock = Socket()
            protect(sock)
            sock.connect(InetSocketAddress(SERVER, PORT), 10000)
            socket = sock

            val serverOut = sock.getOutputStream()
            val serverIn = sock.getInputStream()

            // أرسل الـ payload
            serverOut.write(PAYLOAD.toByteArray())
            serverOut.flush()

            // انتظر رد السيرفر
            Thread.sleep(500)

            val vpn = vpnFd ?: return
            val vpnIn = FileInputStream(vpn.fileDescriptor)
            val vpnOut = FileOutputStream(vpn.fileDescriptor)

            val buf = ByteArray(4096)

            // Thread لقراءة من السيرفر وكتابة للـ VPN
            thread {
                try {
                    while (running) {
                        val len = serverIn.read(buf)
                        if (len > 0) vpnOut.write(buf, 0, len)
                    }
                } catch (e: Exception) {}
            }

            // قراءة من الـ VPN وكتابة للسيرفر
            while (running) {
                val len = vpnIn.read(buf)
                if (len > 0) {
                    serverOut.write(buf, 0, len)
                    serverOut.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stop() {
        running = false
        socket?.close()
        socket = null
        vpnFd?.close()
        vpnFd = null
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
