package com.mohammadadnan.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class TunnelVpnService : VpnService() {

    private var vpnFd: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var running = false
    private var localProxyThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        thread { start() }
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
        return f
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(8192)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {}
    }

    private fun startLocalSocks(serverHost: String, serverPort: Int, payload: String) {
        val serverSocket = java.net.ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress("127.0.0.1", 10808))

        localProxyThread = thread {
            while (running) {
                try {
                    val client = serverSocket.accept()
                    thread {
                        try {
                            // قراءة SOCKS5 handshake
                            val inp = client.getInputStream()
                            val out = client.getOutputStream()

                            // SOCKS5 greeting
                            val greeting = ByteArray(2)
                            inp.read(greeting)
                            val nMethods = greeting[1].toInt() and 0xFF
                            inp.read(ByteArray(nMethods))
                            out.write(byteArrayOf(0x05, 0x00))

                            // SOCKS5 request
                            val req = ByteArray(4)
                            inp.read(req)
                            val addrType = req[3].toInt()
                            val targetHost: String
                            when (addrType) {
                                0x01 -> {
                                    val ip = ByteArray(4); inp.read(ip)
                                    targetHost = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                                }
                                0x03 -> {
                                    val len = inp.read()
                                    val host = ByteArray(len); inp.read(host)
                                    targetHost = String(host)
                                }
                                else -> { client.close(); return@thread }
                            }
                            val portBytes = ByteArray(2); inp.read(portBytes)
                            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                            // الاتصال بالسيرفر
                            val remote = Socket()
                            protect(remote)
                            remote.connect(InetSocketAddress(serverHost, serverPort), 10000)

                            val rOut = remote.getOutputStream()
                            val rIn = remote.getInputStream()

                            // إرسال البايلود
                            val payloadBytes = payload.toByteArray()
                            rOut.write(payloadBytes)
                            rOut.write("\r\n\r\n".toByteArray())
                            rOut.flush()

                            // قراءة رد 200 OK
                            val response = ByteArray(1024)
                            val rLen = rIn.read(response)
                            val responseStr = String(response, 0, rLen)

                            if (!responseStr.contains("200")) {
                                client.close(); remote.close(); return@thread
                            }

                            // إرسال CONNECT للـ xray
                            val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
                            rOut.write(connectReq.toByteArray())
                            rOut.flush()

                            // قراءة رد xray
                            val xrayResp = ByteArray(1024)
                            val xLen = rIn.read(xrayResp)

                            // رد SOCKS5 نجاح
                            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            out.flush()

                            // pipe في الاتجاهين
                            thread { pipe(inp, rOut) }
                            pipe(rIn, out)

                        } catch (e: Exception) {
                            try { client.close() } catch (ex: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun start() {
        running = true
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val server = prefs.getString("server", "51.254.130.47")!!
        val port = prefs.getString("port", "80")!!.toIntOrNull() ?: 80
        val payload = prefs.getString("payload", "HTTP/78 2026")!!

        showNotification("$server:$port")

        // تشغيل local SOCKS5 proxy
        startLocalSocks(server, port, payload)

        Thread.sleep(1000)

        // إنشاء VPN interface
        vpnFd = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setSession("Mohammad Adnan VPN")
            .establish() ?: return

        // تشغيل tun2socks
        val tun2socks = extractBinary("tun2socks")
        tun2socksProcess = ProcessBuilder(
            tun2socks.absolutePath,
            "-device", "fd://${vpnFd!!.fd}",
            "-proxy", "socks5://127.0.0.1:10808",
            "-loglevel", "warning"
        ).redirectErrorStream(true).start()

        thread {
            tun2socksProcess!!.inputStream.bufferedReader().forEachLine { }
        }
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
