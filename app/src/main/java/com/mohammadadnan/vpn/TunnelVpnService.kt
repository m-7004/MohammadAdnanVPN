package com.mohammadadnan.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.*
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    private var vpnFd: ParcelFileDescriptor? = null
    private var running = false
    private val logFile by lazy { File("/sdcard/Download/vpn_log.txt") }
    private val connections = mutableListOf<Socket>()

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

    // فتح tunnel مع السيرفر عبر البايلود
    private fun openTunnel(server: String, port: Int, payload: String): Socket? {
        return try {
            val sock = Socket()
            protect(sock)
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(server, port), 10000)

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            out.write((payload + "\r\n\r\n").toByteArray())
            out.flush()

            // انتظر الرد
            sock.soTimeout = 5000
            try {
                val buf = ByteArray(256)
                val n = inp.read(buf)
                if (n > 0) {
                    val resp = String(buf, 0, n)
                    log("Server: $resp")
                }
            } catch (e: Exception) { }
            sock.soTimeout = 0
            log("Tunnel open to $server:$port")
            sock
        } catch (e: Exception) {
            log("Tunnel error: ${e.message}")
            null
        }
    }

    // SOCKS5 server محلي يوجه عبر tunnel
    private fun startSocks5Server(server: String, port: Int, payload: String) {
        val serverSock = ServerSocket()
        serverSock.reuseAddress = true
        serverSock.bind(InetSocketAddress("127.0.0.1", 10808))
        log("SOCKS5 listening on 10808")

        thread {
            while (running) {
                try {
                    val client = serverSock.accept()
                    thread { handleSocks5(client, server, port, payload) }
                } catch (e: Exception) { if (running) log("SOCKS5 accept error: ${e.message}") }
            }
        }
    }

    private fun handleSocks5(client: Socket, server: String, port: Int, payload: String) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // SOCKS5 handshake
            val ver = inp.read()
            if (ver != 5) { client.close(); return }
            val nMethods = inp.read()
            repeat(nMethods) { inp.read() }
            out.write(byteArrayOf(5, 0))

            // SOCKS5 request
            inp.read() // ver
            val cmd = inp.read()
            inp.read() // rsv
            val atyp = inp.read()

            val targetHost: String
            when (atyp) {
                1 -> {
                    val ip = ByteArray(4)
                    inp.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress!!
                }
                3 -> {
                    val len = inp.read()
                    val host = ByteArray(len)
                    inp.read(host)
                    targetHost = String(host)
                }
                4 -> {
                    val ip = ByteArray(16)
                    inp.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress!!
                }
                else -> { client.close(); return }
            }
            val portHigh = inp.read()
            val portLow = inp.read()
            val targetPort = (portHigh shl 8) or portLow

            log("SOCKS5 → $targetHost:$targetPort")

            // فتح tunnel للسيرفر
            val tunnel = openTunnel(server, port, payload)
            if (tunnel == null) {
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                client.close()
                return
            }

            // إرسال CONNECT للـ xray على السيرفر
            val tOut = tunnel.getOutputStream()
            val tIn = tunnel.getInputStream()

            val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
            tOut.write(connectReq.toByteArray())
            tOut.flush()

            // قراءة رد xray
            tunnel.soTimeout = 5000
            val respBuf = StringBuilder()
            try {
                val b = ByteArray(1)
                while (true) {
                    inp.read(b)
                    respBuf.append(b[0].toChar())
                    if (respBuf.endsWith("\r\n\r\n")) break
                    if (respBuf.length > 512) break
                }
            } catch (e: Exception) {}
            tunnel.soTimeout = 0

            // رد SOCKS5 نجاح
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // pipe
            thread { pipe(inp, tOut) }
            pipe(tIn, out)

        } catch (e: Exception) {
            log("SOCKS5 handler error: ${e.message}")
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun start() {
        running = true
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!.toIntOrNull() ?: 80
        val payload = prefs.getString("payload", "HTTP/78 2026")!!

        log("Starting $server:$port")
        showNotification("$server:$port")

        startSocks5Server(server, port, payload)
        Thread.sleep(500)

        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish()

        if (vpnFd == null) { log("vpnFd null"); return }
        log("VPN fd=${vpnFd!!.fd}")

        val vpnIn = FileInputStream(vpnFd!!.fileDescriptor)
        val vpnOut = FileOutputStream(vpnFd!!.fileDescriptor)

        // packet handler
        thread {
            val buf = ByteArray(32768)
            val bb = java.nio.ByteBuffer.wrap(buf)
            while (running) {
                try {
                    bb.clear()
                    val n = vpnIn.read(buf)
                    if (n <= 0) continue
                    bb.limit(n)
                    handlePacket(buf, n, vpnOut, server, port, payload)
                } catch (e: Exception) { if (running) log("packet error: ${e.message}") }
            }
        }

        log("VPN running")
    }

    private fun handlePacket(buf: ByteArray, n: Int, vpnOut: OutputStream, server: String, port: Int, payload: String) {
        // IP header - protocol
        if (n < 20) return
        val version = (buf[0].toInt() and 0xFF) shr 4
        if (version != 4) return

        val protocol = buf[9].toInt() and 0xFF
        val destIp = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}.${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"

        if (protocol == 6) { // TCP
            val ihl = (buf[0].toInt() and 0x0F) * 4
            val destPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            // نتجاهل الحين — tun2socks يتولى هذا
        }
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
