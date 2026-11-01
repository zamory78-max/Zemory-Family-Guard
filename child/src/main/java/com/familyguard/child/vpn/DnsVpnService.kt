package com.familyguard.child.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.familyguard.child.Domains
import com.familyguard.child.Guard
import com.familyguard.child.web.WebLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Local "DNS only" VPN. Only the fake DNS address is routed into the tunnel, so normal traffic
 * is untouched; every DNS query is logged, blocked domains get NXDOMAIN, and Safe Search
 * hostnames are rewritten. Runs as always-on VPN when we are Device Owner.
 */
class DnsVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(6)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        try {
            val b = Builder()
                .setSession("Zemory Family Guard")
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setBlocking(true)
                .setMtu(1500)
            try {
                b.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            val fd = b.establish() ?: return
            tun = fd
            Guard.vpnRunning = true
            reader = Thread({ readLoop(fd) }, "fg-dns").also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            Guard.vpnRunning = false
        }
    }

    private fun stopTunnel() {
        Guard.vpnRunning = false
        runCatching { tun?.close() }
        tun = null
        reader?.interrupt()
        reader = null
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (tun === fd) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            try {
                pool.execute {
                    try {
                        handle(pkt, output)
                    } catch (e: Exception) {
                        Log.d(TAG, "packet", e)
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return
        val udpLen = ((p[ihl + 4].toInt() and 0xff) shl 8) or (p[ihl + 5].toInt() and 0xff)
        val off = ihl + 8
        val len = minOf(udpLen - 8, p.size - off)
        if (len < 12) return
        val query = p.copyOfRange(off, off + len)
        val q = DnsMessage.question(query) ?: return
        val answer = resolve(query, q) ?: return
        val reply = ipUdpReply(p, ihl, answer)
        synchronized(out) { out.write(reply) }
    }

    private fun resolve(query: ByteArray, q: DnsMessage.Question): ByteArray? {
        val name = q.name.trimEnd('.')
        val pol = Guard.policy
        val blocked = pol.webFilter && Domains.isBlocked(name, pol.blockedDomains)
        if (q.type == DnsMessage.TYPE_A || q.type == DnsMessage.TYPE_AAAA || blocked) {
            WebLog.domain(name, blocked)
        }
        if (blocked) return DnsMessage.nxdomain(query, q)

        if (pol.safeSearch) {
            val target = safeTarget(name)
            if (target != null) {
                if (q.type == DnsMessage.TYPE_A) {
                    val ip = safeIp(target)
                    if (ip != null) return DnsMessage.aRecord(query, q, ip)
                } else if (q.type == DnsMessage.TYPE_AAAA || q.type == DnsMessage.TYPE_HTTPS) {
                    return DnsMessage.empty(query, q)
                }
            }
        }
        return forward(query)
    }

    private fun upstreams(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.forEach {
                if (it.hostAddress != DNS_ADDR) list += it
            }
        }
        list += InetAddress.getByName("8.8.8.8")
        list += InetAddress.getByName("1.1.1.1")
        return list
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstreams()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(query, query.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    return rb.copyOf(rp.length)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun ipUdpReply(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 28 + dns.size
        val r = ByteArray(total)
        r[0] = 0x45
        r[2] = (total shr 8).toByte()
        r[3] = total.toByte()
        r[6] = 0x40
        r[8] = 64
        r[9] = 17
        System.arraycopy(req, 16, r, 12, 4) // source = original destination
        System.arraycopy(req, 12, r, 16, 4) // destination = original source
        val cs = checksum(r, 0, 20)
        r[10] = (cs shr 8).toByte()
        r[11] = cs.toByte()
        r[20] = req[ihl + 2]
        r[21] = req[ihl + 3]
        r[22] = req[ihl]
        r[23] = req[ihl + 1]
        val ul = 8 + dns.size
        r[24] = (ul shr 8).toByte()
        r[25] = ul.toByte()
        System.arraycopy(dns, 0, r, 28, dns.size)
        return r
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    companion object {
        private const val TAG = "FG-Vpn"
        const val ACTION_STOP = "com.familyguard.child.VPN_STOP"
        private const val VPN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"

        private val safeIps = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
        private val googleRe = Regex("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$")

        fun safeTarget(name: String): String? = when {
            googleRe.matches(name) -> "forcesafesearch.google.com"
            name == "www.youtube.com" || name == "m.youtube.com" || name == "youtube.com" ||
                name == "youtubei.googleapis.com" || name == "youtube.googleapis.com" ||
                name == "www.youtube-nocookie.com" -> "restrict.youtube.com"
            name == "www.bing.com" || name == "bing.com" -> "strict.bing.com"
            name == "duckduckgo.com" || name == "www.duckduckgo.com" -> "safe.duckduckgo.com"
            else -> null
        }

        private fun safeIp(target: String): ByteArray? {
            val now = System.currentTimeMillis()
            safeIps[target]?.let { if (now - it.second < 3_600_000L) return it.first }
            val ip = runCatching {
                InetAddress.getAllByName(target).firstOrNull { it is Inet4Address }?.address
            }.getOrNull() ?: return null
            safeIps[target] = ip to now
            return ip
        }

        fun start(ctx: Context) {
            if (prepare(ctx) != null) return // user consent still needed (not Device Owner)
            runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java)) }
                .onFailure { Log.w(TAG, "startService", it) }
        }

        fun stop(ctx: Context) {
            if (!Guard.vpnRunning) return
            runCatching {
                ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
