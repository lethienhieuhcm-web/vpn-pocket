package one.lethanhdong.vpnpocket

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P2.4: DNS intercept → DoH.
 *
 * App gửi UDP DNS tới 10.8.0.1:53 (hoặc bất kỳ DNS nào) → ta chặn,
 * gửi query thô qua tunnel bằng DoH (Go: ResolveDoH → dns.google:443),
 * rồi trả UDP response về cho app.
 *
 * Không parse nội dung DNS ở Kotlin — query/response là bytes thô.
 */
class DnsHandler(
    private val tunnelManager: TunnelManager,
    private val writeTun: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "PocketVpn"
        private const val DNS_PORT = 53
        private const val UDP_PROTOCOL = 17
    }

    /** Xử lý 1 IP packet UDP từ forwarder. Trả true nếu đã xử lý (DNS). */
    fun handleUdpPacket(pkt: ByteArray): Boolean {
        if (pkt.isEmpty()) return false
        return when (pkt[0].toInt() shr 4) {
            4 -> handleV4(pkt)
            6 -> handleV6(pkt)
            else -> false
        }
    }

    private fun handleV4(pkt: ByteArray): Boolean {
        if (pkt.size < 20) return false
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 8) return false
        if ((pkt[9].toInt() and 0xFF) != UDP_PROTOCOL) return false
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        val udp = pkt.copyOfRange(ihl, pkt.size)
        return handleUdp(srcIp, dstIp, udp, isV6 = false)
    }

    private fun handleV6(pkt: ByteArray): Boolean {
        if (pkt.size < 48) return false
        if ((pkt[6].toInt() and 0xFF) != UDP_PROTOCOL) return false
        val srcIp = pkt.copyOfRange(8, 24)
        val dstIp = pkt.copyOfRange(24, 40)
        val udp = pkt.copyOfRange(40, pkt.size)
        return handleUdp(srcIp, dstIp, udp, isV6 = true)
    }

    private fun handleUdp(srcIp: ByteArray, dstIp: ByteArray, udp: ByteArray, isV6: Boolean): Boolean {
        if (udp.size < 8) return false
        val bb = ByteBuffer.wrap(udp).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.short.toInt() and 0xFFFF
        val dstPort = bb.short.toInt() and 0xFFFF
        if (dstPort != DNS_PORT) return false // không phải DNS → P2.5 drop
        val query = udp.copyOfRange(8, udp.size)
        if (query.size < 12) return false

        // Resolve ở thread riêng (DoH blocking).
        Thread({
            try {
                val tunnel = tunnelManager.getTunnel() ?: run {
                    Log.w(TAG, "DoH: no tunnel")
                    return@Thread
                }
                val resp = tunnel.resolveDoH(query) ?: run {
                    Log.w(TAG, "DoH: empty response")
                    return@Thread
                }
                val pkt = buildUdpResponse(
                    srcIp = dstIp, dstIp = srcIp, // đảo chiều
                    srcPort = dstPort, dstPort = srcPort,
                    payload = resp, isV6 = isV6
                )
                writeTun(pkt)
            } catch (e: Exception) {
                Log.w(TAG, "DoH failed: ${e.message}")
            }
        }, "doh-resolve").apply { isDaemon = true; start() }
        return true
    }

    private fun buildUdpResponse(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, isV6: Boolean
    ): ByteArray {
        val udpLen = 8 + payload.size
        return if (isV6) {
            val total = 40 + udpLen
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0x60000000)
            buf.putShort(udpLen.toShort())
            buf.put(UDP_PROTOCOL.toByte()); buf.put(64.toByte())
            buf.put(srcIp); buf.put(dstIp)
            putUdpHeader(buf, srcPort, dstPort, udpLen)
            buf.put(payload)
            val udpSeg = buf.array().copyOfRange(40, total)
            buf.putShort(40 + 6, udpChecksumV6(srcIp, dstIp, udpSeg).toShort())
            buf.array()
        } else {
            val total = 20 + udpLen
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x45.toByte()); buf.put(0)
            buf.putShort(total.toShort())
            buf.putShort((Math.random() * 0xFFFF).toInt().toShort())
            buf.putShort(0x4000.toShort())
            buf.put(64.toByte()); buf.put(UDP_PROTOCOL.toByte())
            buf.putShort(0)
            buf.put(srcIp); buf.put(dstIp)
            val ipCsum = ipChecksum(buf.array().copyOfRange(0, 20))
            buf.putShort(10, ipCsum.toShort())
            putUdpHeader(buf, srcPort, dstPort, udpLen)
            buf.put(payload)
            val udpSeg = buf.array().copyOfRange(20, total)
            val csum = udpChecksumV4(srcIp, dstIp, udpSeg)
            // UDP checksum 0 nghĩa là "không có" ở IPv4 — nếu tính ra 0 thì để 0xFFFF.
            buf.putShort(20 + 6, (if (csum == 0) 0xFFFF else csum).toShort())
            buf.array()
        }
    }

    private fun putUdpHeader(buf: ByteBuffer, srcPort: Int, dstPort: Int, udpLen: Int) {
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // checksum tính sau
    }

    private fun ipChecksum(data: ByteArray): Int {
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

    private fun udpChecksumV4(srcIp: ByteArray, dstIp: ByteArray, udp: ByteArray): Int {
        val pseudo = ByteBuffer.allocate(12 + udp.size).order(ByteOrder.BIG_ENDIAN)
        pseudo.put(srcIp); pseudo.put(dstIp)
        pseudo.put(0); pseudo.put(UDP_PROTOCOL.toByte())
        pseudo.putShort(udp.size.toShort())
        pseudo.put(udp)
        return ipChecksum(pseudo.array())
    }

    private fun udpChecksumV6(srcIp: ByteArray, dstIp: ByteArray, udp: ByteArray): Int {
        val pseudo = ByteBuffer.allocate(40 + udp.size).order(ByteOrder.BIG_ENDIAN)
        pseudo.put(srcIp); pseudo.put(dstIp)
        pseudo.putInt(udp.size)
        pseudo.putInt(UDP_PROTOCOL)
        pseudo.put(udp)
        return ipChecksum(pseudo.array())
    }
}
