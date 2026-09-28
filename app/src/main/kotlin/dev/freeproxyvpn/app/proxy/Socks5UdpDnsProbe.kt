package dev.freeproxyvpn.app.proxy

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * tun2socks tunnels UDP (including DNS) through the upstream proxy's own
 * transport. Plain HTTP proxies are TCP-only (CONNECT method), so DNS over
 * such a proxy simply cannot work - only SOCKS5 proxies that actually honor
 * UDP ASSOCIATE can carry DNS traffic. This probe performs a real SOCKS5 UDP
 * ASSOCIATE handshake and a live DNS query through the resulting relay to
 * find out whether a given SOCKS5 node is one of those, instead of guessing.
 */
object Socks5UdpDnsProbe {

    /** Returns true only if a real DNS query round-tripped through the proxy's UDP relay. */
    fun probe(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        var control: Socket? = null
        var udp: DatagramSocket? = null
        return try {
            control = Socket()
            control.connect(InetSocketAddress(host, port), timeoutMs)
            control.soTimeout = timeoutMs

            val out = control.getOutputStream()
            val `in` = control.getInputStream()

            // Greeting: SOCKS5, 1 auth method, no-auth.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = ByteArray(2)
            if (!readFully(`in`, greet)) return false
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) return false // only no-auth supported

            // UDP ASSOCIATE request; client address/port left as 0.0.0.0:0.
            out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()

            val replyHeader = ByteArray(4)
            if (!readFully(`in`, replyHeader)) return false
            if (replyHeader[1] != 0x00.toByte()) return false // REP != succeeded

            val relayAddr = when (replyHeader[3].toInt()) {
                0x01 -> { // IPv4
                    val a = ByteArray(4)
                    if (!readFully(`in`, a)) return false
                    java.net.InetAddress.getByAddress(a)
                }
                0x03 -> { // domain name
                    val lenB = ByteArray(1)
                    if (!readFully(`in`, lenB)) return false
                    val nameBytes = ByteArray(lenB[0].toInt() and 0xFF)
                    if (!readFully(`in`, nameBytes)) return false
                    java.net.InetAddress.getByName(String(nameBytes, Charsets.US_ASCII))
                }
                else -> return false // IPv6 relay: skip, keep probe simple
            }
            val portB = ByteArray(2)
            if (!readFully(`in`, portB)) return false
            val relayPort = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
            // Some proxies report an unspecified bind address (0.0.0.0) and expect
            // the client to just keep using the proxy's own IP for the data channel.
            val effectiveRelayHost = if (relayAddr.isAnyLocalAddress) host else relayAddr.hostAddress

            udp = DatagramSocket()
            udp.soTimeout = timeoutMs

            val dnsQuery = buildDnsQuery("example.com")
            val wrapped = wrapSocks5Udp(dstHost = "1.1.1.1", dstPort = 53, payload = dnsQuery)
            val sendPacket = DatagramPacket(
                wrapped, wrapped.size,
                InetSocketAddress(effectiveRelayHost, relayPort),
            )
            udp.send(sendPacket)

            val recvBuf = ByteArray(600)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            udp.receive(recvPacket) // throws SocketTimeoutException if nothing comes back
            recvPacket.length > 10
        } catch (e: IOException) {
            false
        } catch (t: Throwable) {
            false
        } finally {
            udp?.close()
            try { control?.close() } catch (_: IOException) {}
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun wrapSocks5Udp(dstHost: String, dstPort: Int, payload: ByteArray): ByteArray {
        val addr = java.net.InetAddress.getByName(dstHost).address // IPv4, 4 bytes
        val header = byteArrayOf(
            0, 0, // RSV
            0, // FRAG
            0x01, // ATYP = IPv4
            addr[0], addr[1], addr[2], addr[3],
            (dstPort shr 8).toByte(), (dstPort and 0xFF).toByte(),
        )
        return header + payload
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val id = Random.nextInt(0, 0xFFFF)
        val header = byteArrayOf(
            (id shr 8).toByte(), (id and 0xFF).toByte(),
            0x01, 0x00, // standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val qname = buildList {
            domain.split(".").forEach { label ->
                add(label.length.toByte())
                label.forEach { add(it.code.toByte()) }
            }
            add(0)
        }.toByteArray()
        val qtypeQclass = byteArrayOf(0x00, 0x01, 0x00, 0x01) // A, IN
        return header + qname + qtypeQclass
    }
}
