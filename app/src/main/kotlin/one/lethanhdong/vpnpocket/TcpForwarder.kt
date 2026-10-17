package one.lethanhdong.vpnpocket

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * P2.3: TCP forwarder — đọc IP packet từ TUN, parse TCP, nối qua tunnel.
 *
 * Luồng: app SYN → dial tunnel (thread riêng) → SYN-ACK → data 2 chiều.
 * Spec: MSS clamp 1360 (§3.4), TCP-only ở Mode A.
 */
class TcpForwarder(
    tunFd: ParcelFileDescriptor,
    private val tunnelManager: TunnelManager,
    private val onUdp: (packet: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "PocketVpn"
        private const val MSS = 1360 // spec §3.4
        private const val TCP_PROTOCOL = 6
        private const val UDP_PROTOCOL = 17
    }

    private val tunIn = FileInputStream(tunFd.fileDescriptor)
    private val tunOut = FileOutputStream(tunFd.fileDescriptor)
    private val outLock = Any()
    private val running = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, TcpConnection>()
    private var readThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({ readLoop() }, "tun-read").apply { isDaemon = true; start() }
        Log.i(TAG, "forwarder started")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        readThread?.interrupt()
        connections.values.forEach { it.close() }
        connections.clear()
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
    }

    private fun readLoop() {
        val buf = ByteArray(2048)
        while (running.get()) {
            try {
                val n = tunIn.read(buf)
                if (n <= 0) continue
                handlePacket(buf.copyOf(n))
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun read: ${e.message}")
                break
            }
        }
    }

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.isEmpty()) return
        when (pkt[0].toInt() shr 4) {
            4 -> handleIPv4(pkt)
            6 -> handleIPv6(pkt)
        }
    }

    // ---- IPv4 ----

    private fun handleIPv4(pkt: ByteArray) {
        if (pkt.size < 20) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl) return
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        val payload = pkt.copyOfRange(ihl, pkt.size)
        when (proto) {
            TCP_PROTOCOL -> handleTcp(srcIp, dstIp, payload, isV6 = false)
            UDP_PROTOCOL -> onUdp(pkt)
        }
    }

    private fun handleIPv6(pkt: ByteArray) {
        if (pkt.size < 40) return
        val proto = pkt[6].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(8, 24)
        val dstIp = pkt.copyOfRange(24, 40)
        val payload = pkt.copyOfRange(40, pkt.size)
        when (proto) {
            TCP_PROTOCOL -> handleTcp(srcIp, dstIp, payload, isV6 = true)
            UDP_PROTOCOL -> onUdp(pkt)
        }
    }

    // ---- TCP ----

    private fun handleTcp(srcIp: ByteArray, dstIp: ByteArray, seg: ByteArray, isV6: Boolean) {
        if (seg.size < 20) return
        val bb = ByteBuffer.wrap(seg).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.short.toInt() and 0xFFFF
        val dstPort = bb.short.toInt() and 0xFFFF
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val ack = bb.int.toLong() and 0xFFFFFFFFL
        val flags = bb.get(13).toInt() and 0xFF
        val dataOffset = ((bb.get(12).toInt() and 0xFF) shr 4) * 4
        if (seg.size < dataOffset) return
        val data = seg.copyOfRange(dataOffset, seg.size)

        val key = connKey(srcIp, srcPort, dstIp, dstPort)
        val isSyn = flags and 0x02 != 0
        val isAck = flags and 0x10 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0

        var conn = connections[key]
        if (conn == null) {
            if (!isSyn) {
                // Không phải SYN mà không có connection: trả RST.
                sendTcp(srcIp, dstIp, srcPort, dstPort, 0, seq + data.size, rst = true, isV6 = isV6)
                return
            }
            val tunnel = tunnelManager.getTunnel()
            if (tunnel == null) {
                Log.w(TAG, "no tunnel, dropping SYN to ${ipStr(dstIp)}:$dstPort")
                return
            }
            conn = TcpConnection(
                srcIp = srcIp, dstIp = dstIp,
                srcPort = srcPort, dstPort = dstPort,
                isV6 = isV6,
                writer = ::writeTun
            )
            connections[key] = conn
            conn.startConnect(tunnel, seq) { connections.remove(key) }
            return
        }
        conn.onPacket(seq, ack, data, isAck, isFin, isRst)
    }

    private fun connKey(sIp: ByteArray, sPort: Int, dIp: ByteArray, dPort: Int): String {
        return "${sIp.toHex()}:$sPort-${dIp.toHex()}:$dPort"
    }

    private fun ipStr(ip: ByteArray): String = try {
        InetAddress.getByAddress(ip).hostAddress ?: "?"
    } catch (_: Exception) { "?" }

    internal fun writeTun(pkt: ByteArray) {
        synchronized(outLock) {
            try {
                tunOut.write(pkt)
            } catch (e: Exception) {
                Log.w(TAG, "tun write: ${e.message}")
            }
        }
    }

    internal fun sendTcp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        syn: Boolean = false, ackFlag: Boolean = true,
        fin: Boolean = false, rst: Boolean = false,
        payload: ByteArray = ByteArray(0),
        isV6: Boolean
    ) {
        // Đảo chiều: trả lời app.
        val pkt = if (isV6) {
            buildIPv6Tcp(srcIp = dstIp, dstIp = srcIp,
                srcPort = dstPort, dstPort = srcPort,
                seq = seq, ack = ack,
                syn = syn, ackFlag = ackFlag, fin = fin, rst = rst,
                payload = payload)
        } else {
            buildIPv4Tcp(srcIp = dstIp, dstIp = srcIp,
                srcPort = dstPort, dstPort = srcPort,
                seq = seq, ack = ack,
                syn = syn, ackFlag = ackFlag, fin = fin, rst = rst,
                payload = payload)
        }
        writeTun(pkt)
    }

    // ---- Packet building ----

    private fun buildIPv4Tcp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        syn: Boolean, ackFlag: Boolean, fin: Boolean, rst: Boolean,
        payload: ByteArray
    ): ByteArray {
        val mssOpt = if (syn) byteArrayOf(0x02, 0x04, (MSS shr 8).toByte(), MSS.toByte()) else ByteArray(0)
        val tcpLen = 20 + mssOpt.size
        val totalLen = 20 + tcpLen + payload.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // IPv4 header
        buf.put(0x45.toByte()); buf.put(0) // version/IHL, DSCP
        buf.putShort(totalLen.toShort())
        buf.putShort(Random.nextInt(0xFFFF).toShort()) // identification
        buf.putShort(0x4000.toShort()) // flags: DF
        buf.put(64.toByte()); buf.put(TCP_PROTOCOL.toByte()) // TTL, protocol
        buf.putShort(0) // checksum (tính sau)
        buf.put(srcIp); buf.put(dstIp)
        val ipHeader = buf.array().copyOfRange(0, 20)
        val ipCsum = checksum(ipHeader)
        buf.putShort(10, ipCsum.toShort())
        // TCP header
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt()); buf.putInt(ack.toInt())
        var doff = 5 + mssOpt.size / 4
        buf.put((doff shl 4).toByte())
        var flags = 0
        if (fin) flags = flags or 0x01
        if (syn) flags = flags or 0x02
        if (rst) flags = flags or 0x04
        if (ackFlag) flags = flags or 0x10
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // window
        buf.putShort(0) // checksum (tính sau)
        buf.putShort(0) // urgent
        buf.put(mssOpt)
        buf.put(payload)
        val tcpSeg = buf.array().copyOfRange(20, totalLen)
        val tcpCsum = tcpChecksumV4(srcIp, dstIp, tcpSeg)
        buf.putShort(20 + 16, tcpCsum.toShort())
        return buf.array()
    }

    private fun buildIPv6Tcp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        syn: Boolean, ackFlag: Boolean, fin: Boolean, rst: Boolean,
        payload: ByteArray
    ): ByteArray {
        val mssOpt = if (syn) byteArrayOf(0x02, 0x04, (MSS shr 8).toByte(), MSS.toByte()) else ByteArray(0)
        val tcpLen = 20 + mssOpt.size
        val totalLen = 40 + tcpLen + payload.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // IPv6 header
        buf.putInt(0x60000000) // version 6
        buf.putShort((tcpLen + payload.size).toShort()) // payload length
        buf.put(TCP_PROTOCOL.toByte()); buf.put(64.toByte()) // next header, hop limit
        buf.put(srcIp); buf.put(dstIp)
        // TCP header
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt()); buf.putInt(ack.toInt())
        val doff = 5 + mssOpt.size / 4
        buf.put((doff shl 4).toByte())
        var flags = 0
        if (fin) flags = flags or 0x01
        if (syn) flags = flags or 0x02
        if (rst) flags = flags or 0x04
        if (ackFlag) flags = flags or 0x10
        buf.put(flags.toByte())
        buf.putShort(65535.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(mssOpt)
        buf.put(payload)
        val tcpSeg = buf.array().copyOfRange(40, totalLen)
        val tcpCsum = tcpChecksumV6(srcIp, dstIp, tcpSeg)
        buf.putShort(40 + 16, tcpCsum.toShort())
        return buf.array()
    }

    companion object Net {
        fun checksum(data: ByteArray): Int {
            var sum = 0L
            var i = 0
            while (i + 1 < data.size) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv().toInt() and 0xFFFF
        }

        fun tcpChecksumV4(srcIp: ByteArray, dstIp: ByteArray, tcp: ByteArray): Int {
            val pseudo = ByteBuffer.allocate(12 + tcp.size).order(ByteOrder.BIG_ENDIAN)
            pseudo.put(srcIp); pseudo.put(dstIp)
            pseudo.put(0); pseudo.put(TCP_PROTOCOL.toByte())
            pseudo.putShort(tcp.size.toShort())
            pseudo.put(tcp)
            return checksum(pseudo.array())
        }

        fun tcpChecksumV6(srcIp: ByteArray, dstIp: ByteArray, tcp: ByteArray): Int {
            val pseudo = ByteBuffer.allocate(40 + tcp.size).order(ByteOrder.BIG_ENDIAN)
            pseudo.put(srcIp); pseudo.put(dstIp)
            pseudo.putInt(tcp.size)
            pseudo.putInt(TCP_PROTOCOL)
            pseudo.put(tcp)
            return checksum(pseudo.array())
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }

    /**
     * Một kết nối TCP: state machine + pump data 2 chiều.
     */
    inner class TcpConnection(
        private val srcIp: ByteArray,
        private val dstIp: ByteArray,
        private val srcPort: Int,
        private val dstPort: Int,
        private val isV6: Boolean,
        private val writer: (ByteArray) -> Unit
    ) {
        @Volatile private var state = State.CONNECTING
        // seq app gửi tới (đã trừ SYN).
        @Volatile private var appSeq: Long = 0
        // seq của mình gửi tới app.
        @Volatile private var ourSeq: Long = Random.nextLong(0xFFFFFFFFL)
        @Volatile private var tunnelConn: vpntunnel.Conn? = null
        private var pumpThread: Thread? = null
        private val closed = AtomicBoolean(false)

        private enum class State { CONNECTING, ESTABLISHED, APP_FIN, CLOSED }

        /** Gọi khi nhận SYN: dial tunnel ở thread riêng, xong thì SYN-ACK. */
        fun startConnect(tunnel: vpntunnel.Tunnel, appIsn: Long, onDone: () -> Unit) {
            appSeq = (appIsn + 1) and 0xFFFFFFFFL
            Thread({
                try {
                    val host = InetAddress.getByAddress(dstIp).hostAddress ?: return@Thread
                    val c = tunnel.dialTCP(host, dstPort.toLong())
                    if (closed.get()) { c.close(); return@Thread }
                    tunnelConn = c
                    state = State.ESTABLISHED
                    // SYN-ACK (kèm MSS clamp).
                    sendTcp(srcIp, dstIp, srcPort, dstPort,
                        seq = ourSeq, ack = appSeq,
                        syn = true, ackFlag = true, isV6 = isV6)
                    ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
                    startPump(onDone)
                } catch (e: Exception) {
                    Log.w(TAG, "dial $host:$dstPort: ${e.message}")
                    sendTcp(srcIp, dstIp, srcPort, dstPort,
                        seq = 0, ack = (appIsn + 1) and 0xFFFFFFFFL,
                        rst = true, ackFlag = true, isV6 = isV6)
                    close()
                    onDone()
                }
            }, "tcp-dial-$dstPort").apply { isDaemon = true; start() }
        }

        /** Nhận packet từ app cho connection đã có. */
        fun onPacket(seq: Long, ack: Long, data: ByteArray, isAck: Boolean, isFin: Boolean, isRst: Boolean) {
            if (state == State.CLOSED || state == State.CONNECTING) return
            if (isRst) { close(); return }
            val c = tunnelConn ?: return
            // Data: chỉ nhận đúng seq kỳ vọng (đơn giản, app sẽ retransmit).
            if (data.isNotEmpty()) {
                if (seq == appSeq) {
                    try {
                        c.write(data)
                    } catch (e: Exception) {
                        Log.w(TAG, "tunnel write: ${e.message}")
                        close(); return
                    }
                    appSeq = (appSeq + data.size) and 0xFFFFFFFFL
                }
                // ACK cho data (kể cả retransmit).
                sendTcp(srcIp, dstIp, srcPort, dstPort,
                    seq = ourSeq, ack = appSeq, ackFlag = true, isV6 = isV6)
            } else if (isAck && !isFin) {
                // ACK thuần: không cần làm gì (không track unacked).
            }
            if (isFin) {
                // App đóng: ACK FIN, gửi FIN của mình, đóng tunnel.
                sendTcp(srcIp, dstIp, srcPort, dstPort,
                    seq = ourSeq, ack = (seq + 1) and 0xFFFFFFFFL,
                    ackFlag = true, isV6 = isV6)
                sendTcp(srcIp, dstIp, srcPort, dstPort,
                    seq = ourSeq, ack = (seq + 1) and 0xFFFFFFFFL,
                    fin = true, ackFlag = true, isV6 = isV6)
                ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
                state = State.APP_FIN
                close()
            }
        }

        /** Pump: tunnel → app. */
        private fun startPump(onDone: () -> Unit) {
            pumpThread = Thread({
                val c = tunnelConn ?: return@Thread
                try {
                    while (!closed.get()) {
                        val chunk = c.read(32 * 1024) ?: break
                        if (chunk.isEmpty()) continue
                        // Gửi từng chunk (≤ MSS để tránh fragment).
                        var off = 0
                        while (off < chunk.size) {
                            val end = minOf(off + MSS, chunk.size)
                            val piece = chunk.copyOfRange(off, end)
                            sendTcp(srcIp, dstIp, srcPort, dstPort,
                                seq = ourSeq, ack = appSeq,
                                ackFlag = true, payload = piece, isV6 = isV6)
                            ourSeq = (ourSeq + piece.size) and 0xFFFFFFFFL
                            off = end
                        }
                    }
                } catch (e: Exception) {
                    // EOF hoặc lỗi: gửi FIN cho app.
                    if (!closed.get()) {
                        sendTcp(srcIp, dstIp, srcPort, dstPort,
                            seq = ourSeq, ack = appSeq,
                            fin = true, ackFlag = true, isV6 = isV6)
                        ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
                    }
                } finally {
                    close()
                    onDone()
                }
            }, "tcp-pump-$dstPort").apply { isDaemon = true; start() }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            state = State.CLOSED
            pumpThread?.interrupt()
            try { tunnelConn?.close() } catch (_: Exception) {}
            tunnelConn = null
        }
    }
}
