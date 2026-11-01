package com.familyguard.child.vpn

import java.io.ByteArrayOutputStream

/** Minimal DNS message helpers: read the question, build blocked / rewritten answers. */
object DnsMessage {
    class Question(val name: String, val type: Int, val end: Int)

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    fun question(m: ByteArray): Question? {
        if (m.size < 12) return null
        if (u16(m, 4) < 1) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= m.size) return null
            val len = m[i].toInt() and 0xff
            if (len == 0) {
                i++
                break
            }
            if ((len and 0xC0) != 0) return null
            if (i + 1 + len > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(m, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        if (i + 4 > m.size) return null
        return Question(sb.toString().lowercase(), u16(m, i), i + 4)
    }

    private fun w16(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 8) and 0xff)
        o.write(v and 0xff)
    }

    private fun header(query: ByteArray, q: Question, rcode: Int, answers: Int): ByteArrayOutputStream {
        val o = ByteArrayOutputStream()
        o.write(query, 0, 2) // id
        o.write(0x80 or (query[2].toInt() and 0x01)) // QR=1, keep RD
        o.write(0x80 or (rcode and 0x0f)) // RA=1
        w16(o, 1)
        w16(o, answers)
        w16(o, 0)
        w16(o, 0)
        o.write(query, 12, q.end - 12) // question section
        return o
    }

    fun nxdomain(query: ByteArray, q: Question): ByteArray = header(query, q, 3, 0).toByteArray()

    fun empty(query: ByteArray, q: Question): ByteArray = header(query, q, 0, 0).toByteArray()

    fun aRecord(query: ByteArray, q: Question, ipv4: ByteArray): ByteArray {
        val o = header(query, q, 0, 1)
        o.write(0xC0); o.write(0x0C) // pointer to the question name
        w16(o, TYPE_A)
        w16(o, 1) // class IN
        w16(o, 0); w16(o, 300) // TTL
        w16(o, 4)
        o.write(ipv4, 0, 4)
        return o.toByteArray()
    }
}
