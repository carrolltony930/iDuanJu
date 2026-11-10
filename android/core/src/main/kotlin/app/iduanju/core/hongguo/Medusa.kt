package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.ProviderException
import app.iduanju.core.util.u

/**
 * SM3 + (later) X-Medusa mint tables. Tables.bin is loaded from classpath
 * `medusa_tables.bin` when search / signed list is needed.
 */
object Medusa {
    private val sm3IV = uintArrayOf(0x7380166Fu, 0x4914B2B9u, 0x172442D7u, 0xDA8A0600u, 0xA96F30BCu, 0x163138AAu, 0xE38DEE4Du, 0xB0FB0E4Eu)
    private fun rotl(x: UInt, n: UInt): UInt { val nn = n and 31u; return if (nn == 0u) x else (x shl nn.toInt()) or (x shr (32 - nn.toInt())) }
    private fun p0(x: UInt) = x xor rotl(x, 9u) xor rotl(x, 17u)
    private fun p1(x: UInt) = x xor rotl(x, 15u) xor rotl(x, 23u)

    fun sm3(data: ByteArray): ByteArray {
        var v = sm3IV.copyOf()
        val buf = ArrayList<Byte>()
        buf.addAll(data.toList()); buf.add(0x80.toByte())
        while (buf.size % 64 != 56) buf.add(0)
        val bitLen = data.size.toULong() * 8u
        for (i in 7 downTo 0) buf.add(((bitLen shr (i * 8)) and 0xffu).toByte())
        var off = 0
        while (off < buf.size) {
            val w = UIntArray(68)
            for (i in 0 until 16) {
                val o = off + i * 4
                w[i] = (buf[o].toUInt() and 0xffu shl 24) or (buf[o + 1].toUInt() and 0xffu shl 16) or
                    (buf[o + 2].toUInt() and 0xffu shl 8) or (buf[o + 3].toUInt() and 0xffu)
            }
            for (j in 16 until 68) w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15u)) xor rotl(w[j - 13], 7u) xor w[j - 6]
            val w1 = UIntArray(64) { w[it] xor w[it + 4] }
            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]; var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
            for (j in 0 until 64) {
                val tj = if (j < 16) 0x79CC4519u else 0x7A879D8Au
                val ss1 = rotl(rotl(a, 12u) + e + rotl(tj, j.toUInt()), 7u)
                val ss2 = ss1 xor rotl(a, 12u)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c; c = rotl(b, 9u); b = a; a = tt1
                h = g; g = rotl(f, 19u); f = e; e = p0(tt2)
            }
            v = uintArrayOf(v[0] xor a, v[1] xor b, v[2] xor c, v[3] xor d, v[4] xor e, v[5] xor f, v[6] xor g, v[7] xor h)
            off += 64
        }
        return v.flatMap { Bytes.u32BE(it).toList() }.toByteArray()
    }

    // ---- bionic random() ----
    private fun randomSeq(seedIn: UInt, n: Int): List<UInt> {
        val state = UIntArray(31)
        var seed = if (seedIn == 0u) 1u else seedIn
        state[0] = seed
        for (i in 1 until 31) {
            val hi = seed / 127773u; val lo = seed % 127773u
            val tt = 16807L * lo.toLong() - 2836L * hi.toLong()
            seed = tt.toUInt()
            if (seed and 0x8000_0000u != 0u) seed += 0x7FFF_FFFFu
            state[i] = seed
        }
        var f = 3; var r = 0
        fun next(): UInt {
            val v = state[f] + state[r]
            state[f] = v
            f = (f + 1) % 31; r = (r + 1) % 31
            return (v shr 1) and 0x7FFF_FFFFu
        }
        repeat(310) { next() }
        return List(n) { next() }
    }

    private fun u32LE(v: UInt) = intArrayOf((v and 0xffu).toInt(), ((v shr 8) and 0xffu).toInt(), ((v shr 16) and 0xffu).toInt(), ((v shr 24) and 0xffu).toInt())
    private fun u32BE(v: UInt) = u32LE(v).reversedArray()

    private fun t4AndU32(seed: UInt): Pair<IntArray, UInt> { val s = randomSeq(seed, 7); return u32LE(s[5]) to s[6] }

    // ---- protobuf builder ----
    private val headC = intArrayOf(0x16, 0x6f, 0x80, 0x75, 0xe2, 0x85, 0xb2, 0x36, 0xbc, 0xeb, 0x84, 0xca, 0x26, 0xcb, 0x43, 0xe8)
    private const val NOTSET = "!notset!"
    private const val TSC: Long = 1388734
    private const val SENT: Long = 1999997

    private class PB { val d = java.io.ByteArrayOutputStream()
        fun varint(n0: Long) { var n = n0.toULong(); while (n > 0x7fuL) { d.write(((n and 0x7fuL) or 0x80uL).toInt()); n = n shr 7 }; d.write(n.toInt()) }
        fun v(f: Int, x: Long) { varint((f shl 3).toLong()); varint(x) }
        fun b(f: Int, x: ByteArray) { varint(((f shl 3) or 2).toLong()); varint(x.size.toLong()); d.write(x) }
        fun s(f: Int, x: String) = b(f, x.toByteArray())
        fun f32(f: Int, x: ByteArray) { varint(((f shl 3) or 5).toLong()); d.write(x) }
        fun bytes(): ByteArray = d.toByteArray()
    }

    private fun deviceInner(): ByteArray = PB().apply {
        v(1, 2); s(3, "3019"); s(5, NOTSET); s(6, NOTSET)
        for (f in 7..10) v(f, SENT)
        s(11, NOTSET); s(12, NOTSET); s(13, NOTSET)
        v(14, SENT); s(15, NOTSET)
        val fl = byteArrayOf(0xf0.toByte(), 0x23, 0x74, 0xc9.toByte())
        for (f in 16..21) f32(f, fl)
        s(22, NOTSET)
        for (f in listOf(23, 24, 25, 27)) v(f, SENT)
        v(28, 3578012222180); v(29, SENT)
        for (f in 30..37) s(f, NOTSET)
        v(38, SENT)
        v(40, 3578012218524)
    }.bytes()

    private fun f23(f1: Long): ByteArray = PB().apply {
        v(1, f1); v(2, TSC); v(3, TSC); v(7, 12216)
        b(12, deviceInner()); b(13, ByteArray(0)); s(14, NOTSET)
        v(15, 12216); v(18, 5248703274); v(19, 3578012186); v(20, 4045878586)
        v(22, TSC)
    }.bytes()

    fun buildPlainPB(rawURL: String, unix: Long, entropy: ByteArray): ByteArray {
        val seed = ((unix xor 4) and 0xFFFF_FFFFL)
        val f12 = (2 * seed) and 0xFFFF_FFFFL
        val f23f1 = (2 * seed - TSC) and 0xFFFF_FFFFL
        val q = rawURL.indexOf('?')
        val query = if (q >= 0) rawURL.substring(q + 1) else ""
        val f14 = sm3(query.toByteArray()).copyOf(6)
        val f1 = ByteArray(16) { headC[it].toByte() }
        for (i in 0 until 16 step 4) f1[i] = (f1[i].toInt() xor 0x04).toByte()
        val f15 = PB().apply { v(1, 2); for (f in 2..5) v(f, TSC) }.bytes()
        return PB().apply {
            b(1, f1); v(2, 8); v(3, 504419796); s(4, "3019")
            s(6, "1611921764"); s(7, "6.8.1.32"); s(8, "v04.07.01-ml-android")
            v(9, 135135744); b(10, ByteArray(8))
            v(12, f12); b(13, entropy); b(14, f14); b(15, f15)
            s(20, "none"); v(21, 738); b(23, f23(f23f1))
            s(24, """{"cmr":16777216,"cmr2":16777216,"un_h":0,"vpn":0,"kd":694367,"fkd":2980190854,"pd":539550637,"dyn":"","do":0,"tk":true}""")
        }.bytes()
    }

    private fun readVarint(b: IntArray, at: Int): Pair<Long, Int> {
        var v = 0L; var s = 0
        for (n in 0 until 10) {
            if (at + n >= b.size) throw ProviderException.invalid("pb varint")
            val c = b[at + n]
            v = v or ((c and 0x7f).toLong() shl s)
            if (c < 0x80) return v to (n + 1)
            s += 7
        }
        throw ProviderException.invalid("pb varint overflow")
    }

    private fun midFromPB(pb: IntArray): Int {
        var f13: Int? = null; var f14: Int? = null
        var i = 0
        while (i < pb.size) {
            val (key, n) = readVarint(pb, i); i += n
            val fn = (key shr 3).toInt(); val wt = (key and 7).toInt()
            when (wt) {
                0 -> { val (_, n2) = readVarint(pb, i); i += n2 }
                2 -> { val (ln, n2) = readVarint(pb, i); i += n2
                    if (ln > 0) { if (fn == 13) f13 = pb[i] else if (fn == 14) f14 = pb[i] }
                    i += ln.toInt() }
                5 -> i += 4
                1 -> i += 8
                else -> throw ProviderException.invalid("pb wire")
            }
            if (f13 != null && f14 != null) return (f13!! and 0x3f) or ((f14!! and 0x3f) shl 6)
        }
        throw ProviderException.invalid("pb missing f13/f14")
    }

    // ---- ks.go ----
    private val ksSeed = intArrayOf(0xac, 0x1a, 0xda, 0xae, 0x95, 0xa7, 0xaf, 0x94, 0xa5, 0x11, 0x4a, 0xb3, 0xb3, 0xa9, 0x7d, 0xd8,
        0x00, 0x50, 0xaa, 0x0a, 0x39, 0x31, 0x4c, 0x40, 0x52, 0x8c, 0xae, 0xc9, 0x52, 0x56, 0xc2, 0x8c)
    private fun eon(a: UInt, b: UInt): UInt = (a xor b).inv()

    private fun ksTransform(pb: IntArray, sbox: IntArray): IntArray {
        val n = pb.size
        val b = IntArray(n) { 0x20 }
        for (i in 0 until n) {
            val x = pb[i].toUInt()
            val sa = sbox[(i shl 2) % 32].toUInt(); val sb = sbox[((i shl 2) or 1) % 32].toUInt()
            val t = ((x shr 4) and 0xff0u.inv()) or (x shl 4)
            val w10 = eon(t + sa, sb)
            val rol3 = ((w10 shl 3) and 0xFFFF_FFF8u) or ((w10 shr 5) and 7u)
            val w14 = eon(sa, rol3 + sb)
            b[n - 1 - i] = (w14 and 0xffu).toInt()
        }
        val last = b[n - 1].toUInt(); val prev = b[n - 2].toUInt()
        val new0 = ((b[0].toUInt() + eon(last, prev)) and 0xffu)
        val new1 = (((new0 xor last xor 0xfeu) + b[1].toUInt()) and 0xffu)
        b[0] = new0.toInt(); b[1] = new1.toInt()
        if (n > 3) for (j in 2 until n - 1) {
            val p = b[j - 1].toUInt()
            val rot = ((p shr 5) and 7u) or (p shl 3)
            val w17 = eon(b[j - 2].toUInt() xor rot, j.toUInt())
            b[j] = ((b[j].toUInt() + w17) and 0xffu).toInt()
        }
        b[n - 1] = b[n - 1] xor b[n - 2]
        var add0 = 0
        for (k in 1 until n) add0 += b[k]
        b[0] = (((b[0] xor b[1]) + (add0 and 0xff)) and 0xff)
        return b
    }

    private fun sm3i(x: IntArray): IntArray = sm3(ByteArray(x.size) { x[it].toByte() }).map { it.toInt() and 0xff }.toIntArray()

    private fun pureA(pb: IntArray, t4: IntArray): IntArray {
        val sbox = sm3i(ksSeed + t4.copyOf(4) + ksSeed)
        val t2 = t4[2].toUInt(); val t3 = t4[3].toUInt()
        val reg = (((t2 shl 11) or t3) xor t2 xor (t2 shr 5)).inv()
        val buf4 = u32BE(reg)
        val body = ksTransform(pb, sbox)
        val stream = IntArray(8) + body
        val last = stream.size - 1
        return IntArray(stream.size) { i -> buf4[i % 4] xor stream[last - i] }
    }

    // ---- packet.go ----
    private val lane = intArrayOf(1, 7, 2, 6, 3, 4, 0, 5)

    private fun extract32(buf: IntArray): IntArray {
        val out = IntArray(32)
        for (w in 0 until 31) {
            var v = 0
            for (j in 0 until 8) { val i = 8 * w + j; v = v or (((buf[i] shr lane[i % 8]) and 1) shl j) }
            out[w] = v
        }
        return out
    }

    private fun applyPatch(buf: IntArray, aes32: IntArray): IntArray {
        val out = buf.copyOf()
        val n = minOf(out.size, 248)
        for (w in 0 until 31) {
            val b = aes32[w]
            for (j in 0 until 8) {
                val i = 8 * w + j
                if (i >= n) break
                val bit = (b shr j) and 1
                val mask = 1 shl lane[j]
                out[i] = (out[i] and mask.inv() and 0xff) or (bit shl lane[j])
            }
        }
        return out
    }

    fun mint(headTS: Long, pb: ByteArray): ByteArray {
        val tabs = Tables.shared()
        val ts = (headTS and 0xFFFF_FFFFL).toUInt()
        val seed = ((headTS xor 4) and 0xFFFF_FFFFL).toUInt()
        val tab = (ts and 3u).toInt()
        val (t4, u32) = t4AndU32(seed)
        val pbi = IntArray(pb.size) { pb[it].toInt() and 0xff }
        val mid = midFromPB(pbi)
        val seed8 = u32LE(u32) + intArrayOf(0x01, mid and 0xff, mid shr 8, 0x18)
        val a = pureA(pbi, t4)
        val pre = intArrayOf(0xA6) + seed8 + a + intArrayOf(t4[2], t4[3])
        if (pre.size < 248) throw ProviderException.invalid("medusa body short")
        val aes32 = tabs.transform32(extract32(pre.copyOf(248)), tab)
        val body = applyPatch(pre, aes32)
        val head = u32LE(ts).toMutableList()
        for (i in 0 until 16) head.add(headC[i] xor head[i % 4])
        val out = head.toIntArray() + intArrayOf(t4[0], t4[1]) + intArrayOf(0x00, 0x01, aes32[31]) + body
        return ByteArray(out.size) { out[it].toByte() }
    }

    // ---- tables.go / transform.go ----
    class Matrix(val name: String, val parts: List<String>, val rows: Int, val cols: Int, val words: Int, val bits: Array<LongArray>) {
        fun apply(parts: List<IntArray>): IntArray {
            val featL = ArrayList<Int>(); parts.forEach { p -> p.forEach { featL.add(it) } }; featL.add(1)
            if (featL.size != (rows + 7) / 8) throw ProviderException.invalid("$name feat ${featL.size}")
            val feat = IntArray(featL.size + words * 8); featL.forEachIndexed { i, v -> feat[i] = v }
            val wv = LongArray(words) { w -> var x = 0L; for (k in 0 until 8) x = x or ((feat[w * 8 + k].toLong() and 0xff) shl (8 * k)); x }
            val out = IntArray((cols + 7) / 8)
            for (j in 0 until cols) {
                var p = 0
                val col = bits[j]
                for (w in col.indices) p = p xor java.lang.Long.bitCount(wv[w] and col[w])
                if (p and 1 != 0) out[j / 8] = out[j / 8] or (1 shl (j % 8))
            }
            return out
        }
    }

    class Tab {
        var id = 0; var half = false
        var base = IntArray(0); var sbox = IntArray(0)
        val mat = HashMap<String, Matrix>()
        var r1Rest = ""; var r3Rest = ""
        var outSrc = IntArray(0); var outP: List<IntArray> = emptyList(); var outM = IntArray(0)
        fun m(n: String) = mat[n] ?: throw ProviderException.invalid("tab $id missing $n")
    }

    class Tables(raw: ByteArray) {
        val tabs: List<Tab>
        companion object {
            @Volatile private var cached: Tables? = null
            /** Set by the app (assets/medusa_tables.bin) or tests (STV/Resources/medusa_tables.bin). */
            @Volatile var loader: (() -> ByteArray)? = null
            @Synchronized fun shared(): Tables {
                cached?.let { return it }
                val data = loader?.invoke()
                    ?: Medusa::class.java.getResourceAsStream("/medusa_tables.bin")?.use { it.readBytes() }
                    ?: throw ProviderException.invalid("medusa_tables.bin 缺失")
                return Tables(data).also { cached = it }
            }
            private val bitPerm = intArrayOf(5, 4, 0, 2, 1, 6, 3, 7)
            private val identPerm = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
            private val r0Wire = listOf(0 to 8, 1 to 12, 4 to 9, 5 to 13, 8 to 10, 9 to 14, 12 to 11, 13 to 15)
            private val in1Idx = intArrayOf(2, 3, 6, 7, 10, 11, 14, 15)
            private data class O(val w: Int, val i: Int, val m: Int, val ident: Boolean)
            private val outLO = listOf(
                O(1, 8, 149, false), O(1, 14, 243, false), O(2, 3, 237, false), O(2, 5, 55, false),
                O(1, 9, 162, false), O(1, 15, 208, false), O(2, 0, 170, false), O(2, 6, 138, false),
                O(1, 10, 235, false), O(1, 12, 53, false), O(2, 1, 119, false), O(2, 7, 155, false),
                O(1, 11, 78, false), O(1, 13, 19, false), O(2, 2, 84, false), O(2, 4, 156, false))
            private val outHI = listOf(
                O(3, 8, 149, false), O(3, 14, 243, false), O(4, 3, 237, false), O(4, 5, 55, false),
                O(3, 9, 162, false), O(3, 15, 208, false), O(4, 0, 170, false), O(4, 6, 138, false),
                O(3, 10, 235, false), O(3, 12, 53, false), O(4, 1, 119, false), O(4, 7, 155, false),
                O(3, 11, 78, false), O(3, 13, 19, false), O(4, 2, 84, false), O(4, 4, 135, true))
            private fun permuteByte(b: Int): Int { var o = 0; bitPerm.forEachIndexed { i, p -> if (b and (1 shl i) != 0) o = o or (1 shl p) }; return o }
            private fun sboxed(x: IntArray, sb: IntArray) = IntArray(x.size) { sb[x[it]] }
            private fun in1(x: IntArray) = IntArray(in1Idx.size) { x[in1Idx[it]] }
            private fun in2(x: IntArray) = x.copyOfRange(16, 31)
            private fun gather(src: IntArray, i: Int, mask: Int, perm: IntArray): Int {
                val b = if (i < src.size) src[i] else 0
                var o = 0; perm.forEachIndexed { k, p -> if (b and (1 shl p) != 0) o = o or (1 shl k) }
                return o xor mask
            }
            private fun r0(in32: IntArray, base: IntArray): IntArray { val r = base.copyOf(16); for ((a, b) in r0Wire) r[b] = r[b] xor permuteByte(in32[a]); return r }
        }

        init {
            var c = 10
            if (raw.size < 12 || String(raw, 0, 8) != "HGMDTF02") throw ProviderException.invalid("tables magic")
            fun u8(): Int = raw[c++].toInt() and 0xff
            fun u16(): Int { val v = (raw[c].toInt() and 0xff) or ((raw[c + 1].toInt() and 0xff) shl 8); c += 2; return v }
            fun bytes(n: Int): IntArray { val v = IntArray(n) { raw[c + it].toInt() and 0xff }; c += n; return v }
            fun pstr(): String { val n = u8(); val s = String(raw, c, n); c += n; return s }
            fun u64(): Long { var x = 0L; for (k in 0 until 8) x = x or ((raw[c + k].toLong() and 0xff) shl (8 * k)); c += 8; return x }
            val n = u16()
            val arr = arrayOfNulls<Tab>(4)
            repeat(n) {
                val t = Tab()
                t.id = u8(); t.half = u8() == 1
                t.base = bytes(16); t.sbox = bytes(256)
                val nm = u8()
                repeat(nm) {
                    val name = pstr()
                    val np = u8()
                    val parts = List(np) { pstr() }
                    val rows = u16(); val cols = u16(); val words = u16()
                    val bits = Array(cols) { LongArray(words) { u64() } }
                    t.mat[name] = Matrix(name, parts, rows, cols, words, bits)
                }
                t.r1Rest = pstr(); t.r3Rest = pstr()
                repeat(4) { pstr() }
                if (t.half) {
                    t.outSrc = bytes(32)
                    val perm = bytes(256)
                    t.outP = List(32) { perm.copyOfRange(it * 8, (it + 1) * 8) }
                    t.outM = bytes(32)
                }
                if (t.id !in 0..3) throw ProviderException.invalid("tab id")
                arr[t.id] = t
            }
            tabs = arr.map { it ?: throw ProviderException.invalid("missing tab") }
        }

        private fun named(m: Matrix, in32: IntArray, r0v: IntArray, r1v: IntArray?, r2v: IntArray?, r3v: IntArray?, lo: IntArray?): IntArray =
            m.apply(m.parts.map { tok ->
                when (tok) {
                    "R0V" -> r0v; "R1V" -> r1v ?: IntArray(0); "R2V" -> r2v ?: IntArray(0); "R3V" -> r3v ?: IntArray(0)
                    "IN1" -> in1(in32); "IN2" -> in2(in32); "SBOX_LO" -> lo ?: IntArray(0)
                    else -> throw ProviderException.invalid("feat $tok")
                }
            })

        fun transform32(in32: IntArray, id: Int): IntArray {
            val t = tabs[id]; val sb = t.sbox
            val r0v = sboxed(r0(in32, t.base), sb)
            if (!t.half) {
                val r1v = sboxed(t.m("W1").apply(listOf(r0v, in1(in32))), sb)
                val r2v = sboxed(t.m("W2").apply(listOf(r1v, in2(in32))), sb)
                val r3v = sboxed(t.m("W3").apply(listOf(r2v, in2(in32))), sb)
                val r4v = sboxed(t.m("W4").apply(listOf(r3v)), sb)
                val pick = listOf(r1v, r2v, r3v, r4v)
                val out = ArrayList<Int>()
                for (e in outLO) out.add(gather(pick[e.w - 1], e.i, e.m, bitPerm))
                for (e in outHI) out.add(gather(pick[e.w - 1], e.i, e.m, if (e.ident) identPerm else bitPerm))
                return out.toIntArray()
            }
            val r1lo = named(t.m("Wa"), in32, r0v, null, null, null, null)
            val r1 = if (t.r1Rest == "tab1_split") {
                val mid = named(t.m("Wb"), in32, r0v, null, null, null, sboxed(r1lo, sb))
                val hi = named(t.m("Wc"), in32, r0v, null, null, null, null)
                r1lo + mid + hi
            } else r1lo + named(t.m("Wb"), in32, r0v, null, null, null, sboxed(r1lo, sb))
            val r1v = sboxed(r1, sb)
            val r2 = t.mat["W2a"]?.let { w2a -> named(w2a, in32, r0v, r1v, null, null, null) + named(t.m("W2b"), in32, r0v, r1v, null, null, null) }
                ?: named(t.m("W2"), in32, r0v, r1v, null, null, null)
            val r2v = sboxed(r2, sb)
            val r3lo = named(t.m("W3a"), in32, r0v, r1v, r2v, null, null)
            val r3 = if (t.r3Rest == "tab1_split") {
                val mid = named(t.m("W3b"), in32, r0v, r1v, r2v, null, sboxed(r3lo, sb))
                val hi = named(t.m("W3c"), in32, r0v, r1v, r2v, null, null)
                r3lo + mid + hi
            } else r3lo + named(t.m("W3b"), in32, r0v, r1v, r2v, null, sboxed(r3lo, sb))
            val r3v = sboxed(r3, sb)
            val lo = named(t.m("W4a"), in32, r0v, r1v, r2v, r3v, null)
            val hi = named(t.m("W4b"), in32, r0v, r1v, r2v, r3v, null)
            val r4v = sboxed(lo + hi, sb)
            val concat = r1v + r2v + r3v + r4v
            return IntArray(32) { i -> gather(concat, t.outSrc[i], t.outM[i], t.outP[i]) }
        }
    }
}
