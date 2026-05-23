package com.mohammadadnan.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    companion object {
        const val SERVER  = "51.254.130.47"
        const val PORT    = 80
        const val UUID    = "free.facebook.com"
        const val PAYLOAD = "HTTP/78 2026"
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        start()
        return START_STICKY
    }

    private fun start() {
        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()
        running = true
        thread { tunnel() }
    }

    private fun tunnel() {
        val fd = vpnFd ?: return
        val input  = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        try {
            val ch = SocketChannel.open()
            protect(ch.socket())
            ch.connect(InetSocketAddress(SERVER, PORT))

            val handshake = "GET / HTTP/1.1\r\nHost: $UUID\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "X-Payload: $PAYLOAD\r\n\r\n"
            ch.write(ByteBuffer.wrap(handshake.toByteArray()))

            val buf  = ByteArray(32767)
            val sBuf = ByteBuffer.allocate(32767)

            while (running) {
                val len = input.read(buf)
                if (len > 0) ch.write(ByteBuffer.wrap(buf, 0, len))
                sBuf.clear()
                val sLen = ch.read(sBuf)
                if (sLen > 0) output.write(sBuf.array(), 0, sLen)
            }
            ch.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stop() {
        running = false
        vpnFd?.close()
        vpnFd = null
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
