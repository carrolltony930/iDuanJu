package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.u

/** Kotlin port of STV/Providers/Hongguo/HGSign.swift (X-Gorgon / Argus / Helios / video_model sign / spade unwrap). */
object HGSign {
    private fun nibbleSwap(b: Int): Int = ((b and 0x0f) shl 4) or ((b shr 4) and 0x0f)
    fun bitReverse(b: Int): Int { var r = 0; for (i in 0 until 8) if (b and (1 shl i) != 0) r = r or (1 shl (7 - i)); return r }

    private fun brokenRC4(key: IntArray, n: Int): IntArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + s[i] + key[i and 7]) and 0xff; s[i] = s[j] }
        var i = 0; var jj = 0
        val out = IntArray(n)
        for (k in 0 until n) {
            i = (i + 1) and 0xff
            jj = (jj + s[i]) and 0xff
            val v = s[jj]
            s[i] = v
            out[k] = s[(s[i] + v) and 0xff] and 0xff
        }
        return out
    }

    private fun gorgon20(seed: IntArray): IntArray {
        val buf = seed.copyOf()
        for (n in 0 until minOf(20, buf.size)) {
            buf[n] = nibbleSwap(buf[n])
            val x = buf[n] xor buf[(n + 1) % 20]
            buf[n] = 0x14 xor (bitReverse(x).inv() and 0xff)
        }
        return buf
    }

    fun xGorgon(params: String, ts: Long, variant: Int? = null, body: ByteArray? = null, flags: Int = 0x4481): String {
        val v = variant ?: (((Bytes.randInt(0, 16) shl 12) or Bytes.randInt(0, 256)) and 0xffff)
        val chunkX = if (body != null && body.isNotEmpty()) Bytes.md5(body).copyOfRange(0, 4) else ByteArray(4)
        val concat = Bytes.md5(params).copyOfRange(0, 4) + chunkX + ByteArray(4) + byteArrayOf(0x00, 0x03, 0x0a, 0x04) +
            Bytes.u32BE(ts.toUInt())
        val fh = (flags shr 8) and 0xff; val fl = flags and 0xff
        val key = intArrayOf(0x4a, fh, 0x16, v and 0xff, 0x47, 0x6c, fl, (v shr 8) and 0xff)
        val ks = brokenRC4(key, 20)
        val xored = IntArray(20) { concat.u(it) xor ks[it] }
        val head = intArrayOf(0x84, 0x04, (v shr 8) and 0xff, v and 0xff, fh, fl)
        return (head + gorgon20(xored)).joinToString("") { String.format("%02x", it) }
    }

    fun xArgus(ts: Long): String = Bytes.b64(Bytes.u32LE(ts.toUInt()))

    fun basicHeaders(params: String, body: ByteArray?): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        return mapOf("X-Khronos" to ts.toString(), "X-Gorgon" to xGorgon(params, ts, body = body), "X-Argus" to xArgus(ts))
    }

    // Helios / Ladon (sign_helios_ladon.go)
    private fun ror64(v: ULong, c: Int): ULong { val n = c and 63; return if (n == 0) v else (v shr n) or (v shl (64 - n)) }

    private fun heliosTable(md5hex: ByteArray): ByteArray {
        val ht = ByteArray(288)
        for (i in 0 until 32) ht[i] = md5hex[i]
        val temp = ArrayDeque<ULong>()
        for (k in 0 until 4) temp.addLast(Bytes.readU64LE(ht, k * 8))
        var b0 = temp.removeFirst(); var b8 = temp.removeFirst()
        for (i in 0 until 0x22) {
            val x9 = b0
            var x8 = ror64(b8, 8) + x9
            x8 = x8 xor i.toULong()
            temp.addLast(x8)
            x8 = x8 xor ror64(x9, 61)
            Bytes.writeU64LE(x8, ht, (i + 1) * 8)
            b0 = x8
            b8 = temp.removeFirst()
        }
        return ht
    }

    private fun heliosBlock(ht: ByteArray, blk: ByteArray): ByteArray {
        var d0 = Bytes.readU64LE(blk, 0); var d1 = Bytes.readU64LE(blk, 8)
        for (i in 0 until 0x22) {
            val h = Bytes.readU64LE(ht, i * 8)
            d1 = h xor (d0 + ror64(d1, 8))
            d0 = d1 xor ror64(d0, 0x3d)
        }
        val out = ByteArray(16)
        Bytes.writeU64LE(d0, out, 0); Bytes.writeU64LE(d1, out, 8)
        return out
    }

    fun heliosToken(khronos: Long, r4: ByteArray? = null): String {
        val r = r4 ?: Bytes.random(4)
        val md5hex = Bytes.hex(Bytes.md5(r + "8662".toByteArray())).toByteArray()
        val ht = heliosTable(md5hex)
        var data = "$khronos-1611921764-3019".toByteArray()
        val pad = 16 - data.size % 16
        data += ByteArray(pad) { pad.toByte() }
        var body = ByteArray(0)
        var i = 0
        while (i < data.size) { body += heliosBlock(ht, data.copyOfRange(i, i + 16)); i += 16 }
        return Bytes.b64(r + body.copyOfRange(0, minOf(32, body.size)))
    }

    /** Full 6-header set for search/tab (sign_pure.go pureSignHeadersPB with plain PB). */
    fun fullHeaders(rawURL: String, device: HGDevice): Map<String, String> {
        val headTS = System.currentTimeMillis() / 1000
        val khronos = headTS xor 4
        val q = rawURL.indexOf('?')
        val params = if (q >= 0) rawURL.substring(q + 1) else ""
        val pb = Medusa.buildPlainPB(rawURL, headTS, device.entropy)
        val pkt = Medusa.mint(headTS, pb)
        return mapOf(
            "X-Khronos" to khronos.toString(),
            "X-Helios" to heliosToken(khronos),
            "X-Ladon" to heliosToken(khronos),
            "X-Medusa" to Bytes.b64(pkt),
            "X-Argus" to xArgus(khronos),
            "X-Gorgon" to xGorgon(params, khronos),
        )
    }

    /** Gateway signGenericRequest: X-Gorgon flags 0x0000 + X-Argus (AB-off batch endpoint). */
    fun genericSessionHeaders(rawQuery: String, body: ByteArray, nowMs: Long): Map<String, String> {
        val ts = nowMs / 1000
        return mapOf(
            "X-Gorgon" to xGorgon(rawQuery, ts, body = body, flags = 0),
            "X-Khronos" to ts.toString(),
            "X-Argus" to xArgus(ts),
            "X-SS-STUB" to Bytes.hex(Bytes.md5(body)).uppercase(),
            "X-SS-Req-Ticket" to nowMs.toString(),
        )
    }

    /** video_model/v1 signature (8404401c). */
    fun videoModelHeaders(rawQuery: String, body: ByteArray, nowMs: Long): Map<String, String> {
        val ts = (nowMs / 1000).toUInt()
        val qh = Bytes.md5(rawQuery); val bh = Bytes.md5(body)
        val p = IntArray(20)
        for (i in 0 until 4) { p[i] = qh.u(i); p[4 + i] = bh.u(i) }
        p[12] = 0; p[13] = 6; p[14] = 11; p[15] = 28
        val tsb = Bytes.u32BE(ts); for (i in 0 until 4) p[16 + i] = tsb.u(i)
        val key = intArrayOf(0x44, 0xb9, 0xb9, 0xd9, 0xa4, 0xae, 0xf9, 0xfc, 0xa4, 0x93, 0xaa, 0x75, 0x7c, 0xa3, 0xc2, 0xc4, 0xa4, 0x96, 0x93, 0x8f)
        for (i in 0 until 20) p[i] = p[i] xor key[i]
        for (i in 0 until 20) {
            val rot = ((p[i] shl 4) or (p[i] shr 4)) and 0xff
            val mixed = rot xor p[(i + 1) % 20]
            p[i] = bitReverse(mixed) xor 0xff xor 20
        }
        val g = (intArrayOf(0x84, 0x04, 0x40, 0x1c, 0, 0) + p).joinToString("") { String.format("%02x", it) }
        return mapOf(
            "X-Gorgon" to g,
            "X-Khronos" to ts.toString(),
            "X-SS-STUB" to Bytes.hex(bh).uppercase(),
            "X-SS-Req-Ticket" to nowMs.toString(),
        )
    }

    // spade_a envelope → 32-hex content key (spade.go)
    private fun transformNonmagic(a: IntArray, mode: Int): IntArray {
        var w11 = 0x55; var w12 = 0xfa
        val out = IntArray(a.size)
        for (i in a.indices) {
            val c = a[i]
            val x: Int
            if (i % 2 == 0) { x = w12; w12 = c } else { x = w11; w11 = c }
            val pc = Integer.bitCount(i)
            val delta = if (mode != 0) pc + 0x15 else -0x15 - pc
            out[i] = ((x xor c) + delta) and 0xff
        }
        return out
    }

    private fun swapPairs(b: IntArray): IntArray {
        val o = b.copyOf(); var i = 0
        while (i + 1 < o.size) { val t = o[i]; o[i] = o[i + 1]; o[i + 1] = t; i += 2 }
        return o
    }

    private fun transformMagic(a: IntArray, mode: Int): IntArray {
        val s = swapPairs(a)
        var w12 = 0x55; var w13 = 0xfa
        val out = IntArray(s.size)
        for (i in s.indices) {
            val c = s[i]
            val x: Int; val y: Int
            if (i % 2 == 0) { x = w13; y = w12; w13 = c } else { x = w12; y = w13; w12 = c }
            val pc = Integer.bitCount(i)
            val delta = if (mode != 0) pc + 0x15 else -0x15 - pc
            out[i] = ((x xor c) - y + delta) and 0xff
        }
        return swapPairs(out)
    }

    private fun hexval(ch: Int): Int = when (ch) { in 0x30..0x39 -> ch - 0x30; in 0x61..0x66 -> ch - 0x57; else -> -1 }

    fun unwrap(spade: ByteArray, mode: Int = 0): String? {
        val ln = spade.size
        if (ln < 4) return null
        val v = spade.u(0) xor spade.u(1) xor spade.u(2)
        val hdr = v - 0x30
        if (hdr < 1) return null
        val w22 = ln - v + 0x2f
        if (w22 < 1 || 1 + w22 > ln) return null
        val a = IntArray(w22) { spade.u(1 + it) }
        val si = ln - hdr - 2
        if (si < 0 || si + 1 >= ln || ln - hdr < 0) return null
        val seed = spade.u(si) xor spade.u(si + 1)
        val buf = IntArray(hdr) { seed xor spade.u(ln - hdr + it) }
        val magic = buf.size >= 6 && String(ByteArray(6) { buf[it].toByte() }, Charsets.ISO_8859_1).let { it == "app_v2" || it == "web_v2" }
        val a2 = if (magic) transformMagic(a, mode) else transformNonmagic(a, mode)
        if (a2.isEmpty()) return null
        val hv = hexval(a2[0])
        if (hv < 0) return null
        val remaining = w22 - hv
        if (remaining < 2 || remaining > a2.size) return null
        return String(ByteArray(remaining - 1) { a2[1 + it].toByte() }, Charsets.UTF_8)
    }

    fun contentKey(envelope: String): String? {
        val t = envelope.trim()
        val cands = mutableListOf<ByteArray>()
        Bytes.b64decode(t)?.let { cands.add(it) }
        Bytes.fromHex(t)?.let { cands.add(it) }
        for (raw in cands) {
            val k = unwrap(raw) ?: continue
            val kb = Bytes.fromHex(k)
            if (kb != null && kb.size == 16) return k.lowercase()
        }
        return null
    }
}
