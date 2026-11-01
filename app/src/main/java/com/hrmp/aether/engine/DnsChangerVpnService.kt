package com.tirexnet.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tirexnet.app.MainActivity
import com.tirexnet.app.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * Dedicated DNS-only VPN engine. Only routes pointing at the selected DNS resolver
 * IPs enter this TUN; all other device traffic keeps using the physical network.
 * DNS packets are forwarded through protected UDP sockets, preventing VPN loops.
 */
class DnsChangerVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var input: FileInputStream? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var running = false
    @Volatile private var workers: ExecutorService = Executors.newFixedThreadPool(4)
    private val outputLock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine("DNS Changer disconnected.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            ACTION_START -> {
                startForegroundCompat()
                val raw = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                Thread({
                    try {
                        startEngine(raw)
                    } catch (e: Throwable) {
                        Log.e(TAG, "DNS engine failed", e)
                        stopEngine("DNS Changer failed: ${safeError(e)}", keepEngineKey = true)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }, "TirexDnsStart").apply { isDaemon = true; start() }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startEngine(raw: String) {
        stopTunOnly()
        val profile = DnsChangerProfiles.decode(raw)
            ?: throw IllegalArgumentException("DNS profile is invalid. Check the four IPv4/IPv6 fields.")
        val resolvers = profile.resolverAddresses()
        if (resolvers.isEmpty()) throw IllegalArgumentException("At least one DNS resolver is required.")

        val builder = Builder()
            .setSession("TIREXNET • DNS • ${profile.name}")
            .setMtu(1500)
            .addAddress("10.111.0.1", 32)
            .addAddress("fd42:1111:1111::1", 128)

        var v4Count = 0
        var v6Count = 0
        for (resolver in resolvers) {
            when (resolver) {
                is Inet4Address -> {
                    builder.addDnsServer(resolver)
                    builder.addRoute(resolver, 32)
                    v4Count++
                }
                is Inet6Address -> {
                    builder.addDnsServer(resolver)
                    builder.addRoute(resolver, 128)
                    v6Count++
                }
            }
        }
        ensureWorkers()
        val established = builder.establish()
            ?: throw IllegalStateException("Android refused to establish the DNS VPN interface.")
        tun = established
        input = FileInputStream(established.fileDescriptor)
        output = FileOutputStream(established.fileDescriptor)
        running = true
        isActive = true
        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        prefs.edit()
            .putString("active_engine", "DNS_CHANGER")
            .putString("connection_message", "DNS Changer connected • ${profile.name}")
            .putBoolean("connection_active", true)
            .apply()
        updateNotification("DNS Changer connected • ${profile.name}")
        readTunLoop(resolvers)
    }

    private fun readTunLoop(resolvers: List<InetAddress>) {
        val source = input ?: throw IllegalStateException("DNS tunnel input is unavailable.")
        val packet = ByteArray(32767)
        while (running) {
            val size = try { source.read(packet) } catch (e: Throwable) {
                if (running) Log.w(TAG, "TUN read stopped", e)
                break
            }
            if (size <= 0) continue
            val copy = packet.copyOf(size)
            runCatching { workers.execute { runCatching { handlePacket(copy, resolvers) }.onFailure { Log.w(TAG, "DNS packet relay failed", it) } } }
                .onFailure { if (running) Log.w(TAG, "DNS worker is unavailable", it) }
        }
    }

    private fun handlePacket(packet: ByteArray, resolvers: List<InetAddress>) {
        if (packet.isEmpty()) return
        when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> handleIpv4(packet, resolvers)
            6 -> handleIpv6(packet, resolvers)
        }
    }

    private fun handleIpv4(packet: ByteArray, resolvers: List<InetAddress>) {
        if (packet.size < 28 || (packet[9].toInt() and 0xff) != 17) return
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || packet.size < ihl + 8) return
        val totalLength = u16(packet, 2).coerceAtMost(packet.size)
        if (totalLength < ihl + 8) return
        val udpOffset = ihl
        val sourcePort = u16(packet, udpOffset)
        val destinationPort = u16(packet, udpOffset + 2)
        if (destinationPort != 53) return
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > totalLength) return
        val sourceIp = packet.copyOfRange(12, 16)
        val destinationIp = packet.copyOfRange(16, 20)
        val resolverIp = InetAddress.getByAddress(destinationIp)
        val query = packet.copyOfRange(udpOffset + 8, udpOffset + udpLength)
        if (query.size < 12) return
        val response = queryResolver(query, resolverIp, resolvers) ?: return
        val reply = buildIpv4UdpReply(sourceIp, destinationIp, sourcePort, destinationPort, response, packet)
        writePacket(reply)
    }

    private fun handleIpv6(packet: ByteArray, resolvers: List<InetAddress>) {
        if (packet.size < 48) return
        // Android's resolver emits a base IPv6 header for ordinary DNS/UDP traffic.
        if ((packet[6].toInt() and 0xff) != 17) return
        val payloadLength = u16(packet, 4)
        if (payloadLength < 8 || 40 + payloadLength > packet.size) return
        val udpOffset = 40
        val sourcePort = u16(packet, udpOffset)
        val destinationPort = u16(packet, udpOffset + 2)
        if (destinationPort != 53) return
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpLength > payloadLength) return
        val sourceIp = packet.copyOfRange(8, 24)
        val destinationIp = packet.copyOfRange(24, 40)
        val resolverIp = InetAddress.getByAddress(destinationIp)
        val query = packet.copyOfRange(udpOffset + 8, udpOffset + udpLength)
        if (query.size < 12) return
        val response = queryResolver(query, resolverIp, resolvers) ?: return
        val reply = buildIpv6UdpReply(sourceIp, destinationIp, sourcePort, destinationPort, response)
        writePacket(reply)
    }

    private fun queryResolver(query: ByteArray, packetDestination: InetAddress, resolvers: List<InetAddress>): ByteArray? {
        val sameFamily = resolvers.filter { it.address.size == packetDestination.address.size }
        val otherFamily = resolvers.filter { it.address.size != packetDestination.address.size }
        val ordered = buildList {
            resolvers.firstOrNull { it == packetDestination }?.let { add(it) }
            sameFamily.filter { it != packetDestination }.forEach { add(it) }
            otherFamily.forEach { add(it) }
            if (isEmpty()) addAll(resolvers)
        }.distinct()
        // Primary first; use the other configured DNS addresses only after a timeout/error.
        for (resolver in ordered) {
            val socket = DatagramSocket(null)
            try {
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.bind(InetSocketAddress(0))
                if (!protect(socket)) throw IllegalStateException("Could not protect the DNS socket from the VPN loop.")
                socket.connect(resolver, DNS_PORT)
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(16_384)
                val received = DatagramPacket(buffer, buffer.size)
                socket.receive(received)
                if (received.length >= 12) return received.data.copyOf(received.length)
            } catch (e: Throwable) {
                Log.d(TAG, "Resolver ${resolver.hostAddress} did not answer", e)
            } finally {
                socket.close()
            }
        }
        return null
    }

    private fun buildIpv4UdpReply(
        clientIp: ByteArray, resolverIp: ByteArray, clientPort: Int, resolverPort: Int,
        dns: ByteArray, original: ByteArray
    ): ByteArray {
        val udpLength = 8 + dns.size
        val totalLength = 20 + udpLength
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = original.getOrElse(1) { 0 }
        put16(out, 2, totalLength)
        put16(out, 4, u16(original, 4))
        put16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 17
        System.arraycopy(resolverIp, 0, out, 12, 4)
        System.arraycopy(clientIp, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20, 0))
        put16(out, 20, resolverPort)
        put16(out, 22, clientPort)
        put16(out, 24, udpLength)
        System.arraycopy(dns, 0, out, 28, dns.size)
        val src = resolverIp + clientIp
        val udpSegment = out.copyOfRange(20, totalLength)
        val pseudo = byteArrayOf(0, 17, (udpLength ushr 8).toByte(), udpLength.toByte())
        val udpSum = checksum(src + pseudo + udpSegment, 0, src.size + pseudo.size + udpSegment.size, 0)
        put16(out, 26, if (udpSum == 0) 0xffff else udpSum)
        return out
    }

    private fun buildIpv6UdpReply(
        clientIp: ByteArray, resolverIp: ByteArray, clientPort: Int, resolverPort: Int, dns: ByteArray
    ): ByteArray {
        val udpLength = 8 + dns.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        put16(out, 4, udpLength)
        out[6] = 17
        out[7] = 64
        System.arraycopy(resolverIp, 0, out, 8, 16)
        System.arraycopy(clientIp, 0, out, 24, 16)
        put16(out, 40, resolverPort)
        put16(out, 42, clientPort)
        put16(out, 44, udpLength)
        System.arraycopy(dns, 0, out, 48, dns.size)
        val pseudo = resolverIp + clientIp + byteArrayOf(
            0, 0, (udpLength ushr 8).toByte(), udpLength.toByte(), 0, 0, 0, 17
        )
        val udpSegment = out.copyOfRange(40, out.size)
        val udpSum = checksum(pseudo + udpSegment, 0, pseudo.size + udpSegment.size, 0)
        put16(out, 46, if (udpSum == 0) 0xffff else udpSum)
        return out
    }

    private fun writePacket(packet: ByteArray) {
        val sink = output ?: return
        synchronized(outputLock) {
            if (!running) return
            runCatching { sink.write(packet); sink.flush() }
                .onFailure { Log.w(TAG, "TUN response write failed", it) }
        }
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial.toLong()
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xff) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun startForegroundCompat() {
        val notification = notification("Starting DNS Changer…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_stat_tirex)
            .setContentTitle("TIREXNET • DNS Changer")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "DNS Changer", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun ensureWorkers() {
        if (workers.isShutdown || workers.isTerminated) {
            workers = Executors.newFixedThreadPool(4)
        }
    }

    private fun stopTunOnly() {
        running = false
        isActive = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tun?.close() }
        input = null
        output = null
        tun = null
    }

    private fun stopEngine(message: String, keepEngineKey: Boolean = false) {
        stopTunOnly()
        workers.shutdownNow()
        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("connection_active", false)
            .putString("connection_message", message)
            .putString("active_engine", if (keepEngineKey) "DNS_CHANGER" else "")
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopEngine("DNS Changer permission was revoked.")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine("DNS Changer disconnected.")
        super.onDestroy()
    }

    private fun safeError(error: Throwable): String =
        (error.message ?: "unknown error").take(120).replace('\n', ' ')

    companion object {
        const val ACTION_START = "com.tirexnet.app.DNS_START"
        const val ACTION_STOP = "com.tirexnet.app.DNS_STOP"
        const val EXTRA_PROFILE = "dns_profile"
        private const val TAG = "TirexDnsChanger"
        private const val CHANNEL_ID = "tirex_dns_changer"
        private const val NOTIFICATION_ID = 5312
        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 2200
        @Volatile var isActive: Boolean = false
            private set
    }
}
