package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.u
import java.util.Base64

/**
 * Kotlin port of STV/Providers/Hongguo/HGPlayerSign.swift
 * (gateway/internal/provider/hongguo/playersign, 4edae5c).
 * Full portable player signature set for video_model v1.
 */
object HGPlayerSign {
    data class Entropy(
        var envLaunch: Long, var envPID: Long, var nowSecs: Long,
        var messageRandom: UInt, var random: UInt, var packedRandom: UInt,
        var gorgonRandom: UInt, var heliosRandom: UInt,
    ) {
        companion object {
            fun draw(nowMillis: Long): Entropy {
                val b = Bytes.random(16)
                fun u32(o: Int): UInt =
                    (b.u(o).toUInt()) or (b.u(o + 1).toUInt() shl 8) or (b.u(o + 2).toUInt() shl 16) or (b.u(o + 3).toUInt() shl 24)
                val n = u32(0).toULong() or (u32(4).toULong() shl 32)
                val u16a = (b.u(8) or (b.u(9) shl 8)).toUInt()
                val u16b = (b.u(12) or (b.u(13) shl 8)).toUInt()
                return Entropy(
                    envLaunch = 100 + (n % 21uL).toLong(),
                    envPID = 10001 + (u16a % 2000u).toLong(),
                    nowSecs = nowMillis / 1000,
                    messageRandom = u32(0), random = u32(4), packedRandom = u32(8),
                    gorgonRandom = u16b, heliosRandom = u32(12),
                )
            }
        }
    }

    fun signPOST(
        path: String, pairs: List<Pair<String, String>>, nowMillis: Long,
        deviceID: String, versionName: String, body: ByteArray, entropy: Entropy? = null,
    ): Pair<String, Map<String, String>> {
        var ent = entropy ?: Entropy.draw(nowMillis)
        val bodyMD5 = md5(body)
        val base = nowMillis.toULong()
        var query = ""; var ticket = 0UL; var khronos = 0u
        for (offset in 0UL until 32UL) {
            ticket = base + offset
            khronos = (ticket / 1000UL).toUInt()
            query = encodeQuery(pairs + listOf("ts" to khronos.toString(), "_rticket" to ticket.toString()))
            if (branchOf(sm3(query.toByteArray()), bodyMD5, le32(khronos)) != 1u) break
        }
        if (ent.nowSecs == 0L) ent.nowSecs = khronos.toLong()
        val rawURL = "https://reading.snssdk.com$path?$query"
        val h = linkedMapOf(
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-xs-from-web" to "0",
            "x-ss-req-ticket" to ticket.toString(),
            "x-tt-request-tag" to "t=0;n=0",
            "sdk-version" to "2",
            "passport-sdk-version" to "50561",
            "x-vc-bdturing-sdk-version" to "3.7.2.cn",
            "x-khronos" to khronos.toString(),
            "x-ladon" to Base64.getEncoder().encodeToString(be32(khronos)),
            "x-argus" to Base64.getEncoder().encodeToString(le32(khronos)),
            "x-gorgon" to xGorgon(query, body, khronos, ent.gorgonRandom),
            "x-helios" to helios(khronos, ent.heliosRandom),
            "x-medusa" to medusa(rawURL, body, khronos, deviceID, versionName, ent),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-ss-stub" to bodyMD5.joinToString("") { String.format("%02X", it.toInt() and 0xff) },
        )
        return query to h
    }

    fun encodeQuery(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    private fun enc(s: String): String {
        val un = "-_.!~*'()".map { it.code }.toSet()
        return buildString {
            for (b in s.toByteArray()) {
                val c = b.toInt() and 0xff
                if ((c in 65..90) || (c in 97..122) || (c in 48..57) || c in un) append(c.toChar())
                else append(String.format("%%%02X", c))
            }
        }
    }

    // primitives
    fun rol32(v: UInt, c: UInt): UInt { val n = c and 31u; return if (n == 0u) v else (v shl n.toInt()) or (v shr (32 - n.toInt())) }
    fun ror32(v: UInt, c: UInt): UInt { val n = c and 31u; return if (n == 0u) v else (v shr n.toInt()) or (v shl (32 - n.toInt())) }
    fun ror64(v: ULong, c: ULong): ULong { val n = (c and 63uL).toInt(); return if (n == 0) v else (v shr n) or (v shl (64 - n)) }
    fun reverseBits(v: Int): Int { var r = 0; for (i in 0 until 8) r = (r shl 1) or ((v shr i) and 1); return r }
    fun le32(v: UInt): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun be32(v: UInt): ByteArray = le32(v).reversedArray()
    fun rdLE32(b: ByteArray, o: Int): UInt = b.u(o).toUInt() or (b.u(o + 1).toUInt() shl 8) or (b.u(o + 2).toUInt() shl 16) or (b.u(o + 3).toUInt() shl 24)
    fun rdBE32(b: ByteArray, o: Int): UInt = (b.u(o).toUInt() shl 24) or (b.u(o + 1).toUInt() shl 16) or (b.u(o + 2).toUInt() shl 8) or b.u(o + 3).toUInt()
    fun rdLE64(b: ByteArray, o: Int): ULong = rdLE32(b, o).toULong() or (rdLE32(b, o + 4).toULong() shl 32)
    fun wrLE64(v: ULong): ByteArray = le32((v and 0xffffffffuL).toUInt()) + le32((v shr 32).toUInt())
    fun md5(d: ByteArray): ByteArray = Bytes.md5(d)
    fun hexStr(b: ByteArray): String = Bytes.hex(b)
    fun hexBytes(s: String): ByteArray = Bytes.fromHex(s)!!
    fun hexStr(b: IntArray): String = b.joinToString("") { String.format("%02x", it and 0xff) }

    fun getIV(iv: UInt, data: ByteArray): UInt {
        var v = iv
        for ((i, bb) in data.withIndex()) {
            val b = bb.toInt() and 0xff
            if (i and 1 == 0) { v = (v shr 4) xor v xor (v shl 6) xor b.toUInt(); continue }
            v = ((v shr 7) xor v xor (b.toUInt() or (v shl 12))).inv()
        }
        return v
    }

    fun sumMD5(data: ByteArray): UInt {
        var check = 0x20220420u
        for (i in 0 until 12) {
            val temp: UInt
            if (i and 1 == 0) { temp = (check shr 3) xor check; check = data.u(i).toUInt() xor (check shl 7) }
            else { temp = (check shr 5) xor check; check = (data.u(i).toUInt() or (check shl 11)).inv() }
            check = check xor temp
        }
        return (check or 4u) xor 0x01000000u
    }

    fun sm3(d: ByteArray): ByteArray = Medusa.sm3(d)

    // proto
    fun varint(v0: ULong): ByteArray {
        var v = v0; val o = ArrayList<Byte>()
        while (v > 127u) { o.add(((v and 127u) or 128u).toByte()); v = v shr 7 }
        o.add(v.toByte()); return o.toByteArray()
    }
    fun pKey(tag: UInt, w: Int): ByteArray = varint((tag.toULong() shl 3) or w.toULong())
    fun pBytes(tag: UInt, b: ByteArray): ByteArray = if (b.isEmpty()) ByteArray(0) else pKey(tag, 2) + varint(b.size.toULong()) + b
    fun pStr(tag: UInt, s: String): ByteArray = pBytes(tag, s.toByteArray())
    fun pSInt(tag: UInt, v: Long): ByteArray = pKey(tag, 0) + varint(((v shl 1) xor (v shr 63)).toULong())
    fun pFloat(tag: UInt, v: Float): ByteArray = pKey(tag, 5) + le32(v.toRawBits().toUInt())

    const val appID = "8662"
    const val channelID = "1588093228"
    const val gorgonSDKVersion: UInt = 67503104u

    private val sbox: ByteArray by lazy { Base64.getDecoder().decode(HGPlayerSignTables.SBOX_B64) }
    private val branch2Orders: ByteArray by lazy { hexBytes(HGPlayerSignTables.BRANCH2_ORDERS_HEX) }
    private val medusaSignKey = hexBytes("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
    private val medusaAESKey = hexBytes("f1593376766ea98d34f31b057a9d5be4")
    private val medusaAESIV = hexBytes("1fe109a4125283f418de9e051a969e12")
    private val branch2SV: UIntArray get() = HGPlayerSignTables.BRANCH2_SV

    // gorgon
    fun rc4Gorgon(data: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + s[i] + (key.u(i % key.size))) and 255; s[i] = s[j] }
        val out = ByteArray(data.size); var i = 0; j = 0
        for ((idx, vv) in data.withIndex()) {
            i += 1
            val x = s[i and 255]; j += x
            val y = s[j and 255]; s[i and 255] = y
            out[idx] = (vv.toInt() xor s[(y + y) and 255]).toByte()
        }
        return out
    }

    fun xGorgon(query: String, body: ByteArray, khronos: UInt, random: UInt): String {
        val qm = md5(query.toByteArray()); val bm = md5(body)
        val input = qm.copyOfRange(0, 4) + bm.copyOfRange(0, 4) + ByteArray(4) + le32(gorgonSDKVersion) + be32(khronos)
        val key = byteArrayOf(0x4a, 0x40, 0x16, ((random shr 8) and 255u).toByte(), 0x47, 0x6c, 0x01, (random and 255u).toByte())
        val out = rc4Gorgon(input, key).copyOf()
        val n = out.size
        for (i in 0 until n) {
            val v = out.u(i)
            val sw = (v shr 4) or (v shl 4)
            val next = if (i + 1 < n) out.u(i + 1) else out.u(0)
            out[i] = (reverseBits(next xor sw).inv() xor 20).toByte()
        }
        val head = byteArrayOf(0x84.toByte(), 0x04, (random and 255u).toByte(), ((random shr 8) and 255u).toByte(), 0x40, 0x01)
        return hexStr(head + out)
    }

    // helios
    fun helios(khronos: UInt, random: UInt): String {
        val ascii = hexStr(md5(le32(random) + appID.toByteArray())).toByteArray()
        val words = (0 until 4).map { rdLE64(ascii, it * 8) }
        val table = ArrayList<ULong>(); table.add(words[0])
        var b0 = words[0]; var b8 = words[1]
        val queue = ArrayDeque(words.drop(2))
        for (i in 0UL until 34UL) {
            var x8 = ror64(b8, 8uL) + b0
            x8 = x8 xor i
            queue.addLast(x8)
            x8 = x8 xor ror64(b0, 61uL)
            table.add(x8)
            b0 = x8; b8 = queue.removeFirst()
        }
        val text = "$khronos-$channelID-$appID".toByteArray()
        val fill = (16 - (text.size % 16)).toByte()
        val size = ((text.size + 1 + 15) / 16) * 16
        val padded = ByteArray(size) { fill }; for (i in text.indices) padded[i] = text[i]
        val output = ArrayList<Byte>()
        var off = 0
        while (off < padded.size) {
            var a = rdLE64(padded, off); var b = rdLE64(padded, off + 8)
            for (t in table.take(34)) { b = t xor (a + ror64(b, 8uL)); a = b xor ror64(a, 61uL) }
            output.addAll(wrLE64(a).toList()); output.addAll(wrLE64(b).toList())
            off += 16
        }
        return Base64.getEncoder().encodeToString(le32(random) + output.toByteArray())
    }

    // f13
    private val b0IV = uintArrayOf(0xc4a78580u, 0xb3c0fd39u, 0xc58c5686u, 0xc9aa3ba7u, 0xf5a7adf2u, 0x963c2ed1u)
    private val b0TT = uintArrayOf(
        0xebb64fafu, 0x07aadcc2u, 0xcf3187bfu, 0xe01138ffu, 0x6d0bfcffu, 0x5a30a3beu, 0xb41ad638u, 0x34180eb8u,
        0xf233eb6fu, 0xb1a584ccu, 0xccc30dc7u, 0x47d1db51u, 0xd55653deu, 0x70a84fa1u, 0x57473c12u, 0xf76f0288u,
        0x2c077f0au, 0xda0dcad0u, 0xfbb86f6cu, 0xfdc4cf00u, 0x688a020du, 0xe676c6a6u, 0x8cd6338bu, 0x1a3c8d0eu,
        0xcce8b06bu, 0x6ad0ed0bu, 0xa0522717u, 0xdc71ac83u, 0x2285db71u, 0xd5b4dda6u, 0x736f8650u, 0x6560306cu,
        0x617ce2a6u, 0xe423417eu, 0x0a40e143u, 0x544e4032u, 0x88dffb2au, 0x716c1ae0u, 0x4c467a88u, 0x05b23bb3u,
        0xe1d0b866u, 0xbaa3dcb8u, 0xae3374d3u, 0xc3381a50u, 0x1702f75bu, 0xfe6da368u, 0xf0b4cf48u, 0x4e0ffbb8u,
        0x72aad10du, 0x26c53a3du, 0xf2bce0f6u, 0xb4557581u, 0x4a257fddu, 0x8c3182a2u, 0xab0b3b86u, 0x3d5dfb14u,
        0x4f103634u, 0xd37b52d7u, 0x444eff16u, 0xeb0a33d1u, 0x6ca86f6eu, 0x00284ba7u, 0x08387cfau, 0x5fb37586u,
    )
    private val b0Init = uintArrayOf(0x7aba4fc8u, 0x67166507u, 0x6403fa00u, 0x340f512fu, 984304912u, 3005047866u, 2874125293u, 2152413264u)
    private val b0Rounds = arrayOf(
        uintArrayOf(101u, 5u, 7u, 6u, 3u, 2u, 1u, 0u, 5u, 4u, 3u), uintArrayOf(96u, 0u, 6u, 7u, 5u, 3u, 2u, 1u, 5u, 4u, 4u),
        uintArrayOf(96u, 7u, 6u, 2u, 1u, 4u, 0u, 5u, 4u, 3u, 5u), uintArrayOf(99u, 3u, 6u, 2u, 4u, 5u, 1u, 0u, 0u, 7u, 6u),
        uintArrayOf(96u, 0u, 5u, 6u, 7u, 3u, 1u, 2u, 5u, 4u, 4u), uintArrayOf(100u, 2u, 0u, 3u, 5u, 4u, 6u, 7u, 2u, 1u, 5u),
    )

    fun branchOf(q: ByteArray, bm: ByteArray, ts: ByteArray): UInt {
        val iv = getIV(getIV(getIV(0x20230928u, q), bm), ts)
        val low = iv and 15u
        return low - ((low * 171u) shr 9) * 3u
    }

    fun hashF13(qs: ByteArray, bm: ByteArray, ts: ByteArray, khronos: UInt): ByteArray {
        val iv = getIV(getIV(getIV(0x20230928u, qs), bm), ts)
        val low = iv and 15u
        val ivV0 = (low * 171u) shr 9
        val branch = low - ivV0 * 3u
        if (branch == 2u) return branch2F13(iv, qs, bm, ts, khronos)
        if (branch != 0u) return qs.copyOfRange(0, 16) + bm + le32(sumMD5(qs + bm))
        return branch0F13(qs, bm, ts, khronos, ivV0)
    }

    fun expandWords(data: ByteArray): UIntArray {
        val di = ArrayList<UInt>()
        var off = 0; while (off + 4 <= data.size) { di.add(rdBE32(data, off)); off += 4 }
        var di0 = di[0]
        for (i in 0 until 112) {
            val di1 = di[i + 1]; val di14 = di[i + 14]
            val r1 = rol32(di1, 14u) xor rol32(di1, 25u) xor (di1 shr 3)
            val r2 = rol32(di14, 13u) xor rol32(di14, 15u) xor (di14 shr 10)
            di.add(di0 + di[i + 9] + r1 + r2)
            di0 = di1
        }
        return di.toUIntArray()
    }

    fun mixRounds(st: UIntArray, r: UIntArray, di: UIntArray, tt: UIntArray, ivV1: UInt): UIntArray {
        var s = st.toMutableList()
        fun x(k: Int) = r[k].toInt()
        for (i in 0u until r[0]) {
            val base = ivV1 + i
            val w = di[(base and 127u).toInt()]
            val n1 = ((s[x(3)] xor s[x(4)]) and s[x(1)]) xor s[x(3)]
            val n2 = rol32(s[x(1)], 26u) xor rol32(s[x(1)], 21u) xor rol32(s[x(1)], 7u)
            val n4 = w + n1 + n2 + tt[(base and 63u).toInt()] + s[x(5)]
            val n5 = rol32(s[x(2)], 30u) xor rol32(s[x(2)], 19u) xor rol32(s[x(2)], 10u)
            val n6 = (s[x(2)] and s[x(6)]) or ((s[x(2)] or s[x(6)]) and s[x(7)])
            val n7 = n5 + n6
            val old = s[x(9)]
            s = (listOf(s.last()) + s.dropLast(1)).toMutableList()
            s[x(10)] = n7 + n4
            s[x(8)] = old + n4
        }
        return s.toUIntArray()
    }

    fun branch0F13(qs: ByteArray, bm: ByteArray, ts: ByteArray, khronos: UInt, ivV0: UInt): ByteArray {
        val ivV1 = b0IV[minOf(ivV0.toInt(), b0IV.size - 1)]
        val countV1 = (ivV1 + khronos + 1u) and 255u
        val countV2 = ivV1 + khronos
        val tt = UIntArray(b0TT.size) { ror32(b0TT[it], countV1) }
        val n0 = ((countV2 + 2u) and 7u).toInt()
        val pad = byteArrayOf(0xfa.toByte(), 0x45, 0x61, 0xd7.toByte()).map { v ->
            val c = (v.toInt() and 0xff) or ((v.toInt() and 0xff) shl 8); ((c shr n0) and 255).toByte()
        }.toByteArray()
        val data = qs + bm + ts + pad + hexBytes("00000000000001a0")
        val di = expandWords(data)
        val ini = UIntArray(b0Init.size) { ror32(b0Init[it], countV2 and 31u) }
        val d = mixRounds(ini, b0Rounds[ivV0.toInt()], di, tt, ivV1)
        val ret = ByteArray(32)
        for (i in 0 until 8) be32(d[i] + ini[i]).copyInto(ret, i * 4)
        for (i in 0 until 16) ret[i] = (ret.u(i) xor ret.u(16 + i)).toByte()
        return ret.copyOfRange(0, 16) + le32(sumMD5(ret.copyOfRange(0, 16)))
    }

    fun md5V3Step(kind: UInt, a: UInt, b: UInt, c: UInt, d: UInt, m: UInt, sh: UInt, k: UInt): UInt {
        val f = when (kind.toInt()) {
            0 -> (b and c) or (b.inv() and d)
            1 -> (b and d) or (c and d.inv())
            2 -> b xor c xor d
            else -> c xor (b or d.inv())
        }
        return b + rol32(a + f + m + k, sh)
    }

    fun md5SumV3(msg: ByteArray, countV2: UInt, orders: ByteArray, countV1: UInt): ByteArray {
        val sv = UIntArray(branch2SV.size) { ror32(branch2SV[it], countV1) }
        val start = uintArrayOf(ror32(0x79e0f2fbu, countV2), ror32(0xc8b52570u, countV2), ror32(0xebc2f8cdu, countV2), ror32(0x7c104d93u, countV2))
        val ec = (countV2 + 6u) and 255u
        val end = uintArrayOf(ror32(0x19be4866u, ec), ror32(0xe85986b4u, ec), ror32(0xe19b326eu, ec), ror32(0x71d1d7d4u, ec))
        val m = UIntArray(16) { rdLE32(msg, it * 4) }
        var a = start[0]; var b = start[1]; var c = start[2]; var d = start[3]
        fun run(kind: UInt, shifts: IntArray, offset: Int) {
            for (i in 0 until 16) {
                val w = m[orders.u(offset + i)]; val k = sv[offset + i]
                when (i and 3) {
                    0 -> a = md5V3Step(kind, a, b, c, d, w, shifts[i].toUInt(), k)
                    1 -> d = md5V3Step(kind, d, a, b, c, w, shifts[i].toUInt(), k)
                    2 -> c = md5V3Step(kind, c, d, a, b, w, shifts[i].toUInt(), k)
                    else -> b = md5V3Step(kind, b, c, d, a, w, shifts[i].toUInt(), k)
                }
            }
        }
        run(0u, intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22), 0)
        run(1u, intArrayOf(5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20), 16)
        run(2u, intArrayOf(4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23), 32)
        run(3u, intArrayOf(6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21), 48)
        val ret = le32((start[0] + a) xor end[0]) + le32((start[1] + b) xor end[1]) + le32((start[2] + c) xor end[2]) + le32((start[3] + d) xor end[3])
        return ret + le32(sumMD5(ret))
    }

    fun branch2F13(iv: UInt, qs: ByteArray, bm: ByteArray, ts: ByteArray, khronos: UInt): ByteArray {
        val v = (iv and 13u) * 86u
        val ivV0 = ((v shr 15) and 255u) + ((v shr 8) and 255u)
        val n1s = uintArrayOf(0x8980f29bu, 0xeb549c7fu, 0xb08726dbu, 0xd40cb5e6u, 0xe8f559e4u)
        val n1 = n1s[minOf(ivV0.toInt(), n1s.size - 1)]
        val countV1 = (n1 + khronos + 1u) and 255u
        val countV2 = n1 + khronos
        val sh = ((countV2 + 5u) and 7u).toInt()
        val pad = byteArrayOf(0x84.toByte(), 0x96.toByte(), 0x77, 0x9d.toByte(), 0xd4.toByte(), 0x15, 0x0b, 0xf8.toByte()).map { vv ->
            val c = (vv.toInt() and 0xff) or ((vv.toInt() and 0xff) shl 8); ((c shr sh) and 255).toByte()
        }.toByteArray()
        val input = qs + bm + ts + pad + hexBytes("a0010000")
        val begin = ivV0.toInt() shl 6
        return md5SumV3(input, countV2, branch2Orders.copyOfRange(begin, begin + 64), countV1)
    }

    // aes custom v3
    private val aesCon2 = arrayOf(intArrayOf(1, 0, 2, 3), intArrayOf(2, 0, 3, 1), intArrayOf(0, 1, 3, 2), intArrayOf(1, 0, 2, 3))
    private val aesOrder = arrayOf(
        intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
        intArrayOf(0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15),
        intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
    )
    private val aesInitWord = uintArrayOf(0xca025ddcu, 0x823dc546u, 0xc9420583u, 0xc298225fu)

    fun xorBytes(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { (a.u(it) xor b.u(it % b.size)).toByte() }

    fun aesEncrypt(data: ByteArray, iv: ByteArray, key: ByteArray, khronos: UInt): ByteArray {
        val word = (khronos and 3u).toInt()
        val box = sbox.copyOfRange(word * 256, word * 256 + 256)
        val con2 = aesCon2[word]; val order = aesOrder[word]
        val ini = aesInitWord[word]
        val ex = xorBytes(le32(ini) + le32(ini) + le32(ini) + le32(ini), key) + ByteArray(32)
        var rounds = 8u
        for (i in 4 until 12) {
            val at = 4 * (i - 1)
            var a = ex.u(at); var b = ex.u(at + 1); var c = ex.u(at + 2); var d = ex.u(at + 3)
            if (i and 3 == 0) {
                val t = ((ini shr (rounds and 24u).toInt()) xor box.u(b).toUInt()) and 255u
                b = box.u(c); c = box.u(d); d = box.u(a); a = t.toInt()
            }
            rounds += 2u
            ex[at + 4] = (a xor ex.u(at - 12)).toByte(); ex[at + 5] = (b xor ex.u(at - 11)).toByte()
            ex[at + 6] = (c xor ex.u(at - 10)).toByte(); ex[at + 7] = (d xor ex.u(at - 9)).toByte()
        }
        val keys = (0 until 12).map { ex.copyOfRange(it * 4, it * 4 + 4) }
        fun addCon(s: Array<ByteArray>, k0: Int) { for (i in 0 until 4) for (j in 0 until 4) s[i][j] = (s[i].u(j) xor keys[k0 + i].u(con2[j])).toByte() }
        fun block(v: ByteArray): ByteArray {
            val s = Array(4) { v.copyOfRange(it * 4, it * 4 + 4) }
            addCon(s, 0)
            for (r in 1 until 3) {
                for (i in 0 until 4) for (j in 0 until 4) s[i][j] = box[s[i].u(j)]
                val old = s.map { it.copyOf() }
                for (i in 0 until 4) s[i] = old[con2[i]]
                val flat = s.flatMap { it.toList() }
                for (i in 0 until 16) s[i / 4][i % 4] = flat[order[i]]
                if (r == 1) {
                    val o2 = s.map { it.copyOf() }
                    for (i in 0 until 4) for (j in 0 until 4) s[i][j] = o2[i][con2[j]]
                    fun xt(x: Int): Int { var vv = (x shl 1) and 0xff; if (x and 128 != 0) vv = vv xor 0x1b; return vv }
                    for (i in 0 until 4) {
                        val t = s[0].u(i) xor s[1].u(i) xor s[2].u(i) xor s[3].u(i)
                        val u0 = s[0].u(i)
                        s[0][i] = (s[0].u(i) xor t xor xt(s[0].u(i) xor s[1].u(i))).toByte()
                        s[1][i] = (s[1].u(i) xor t xor xt(s[1].u(i) xor s[2].u(i))).toByte()
                        s[2][i] = (s[2].u(i) xor t xor xt(s[2].u(i) xor s[3].u(i))).toByte()
                        s[3][i] = (s[3].u(i) xor t xor xt(s[3].u(i) xor u0)).toByte()
                    }
                }
                addCon(s, r * 4)
            }
            for (i in 0 until 4) for (j in 0 until 4) s[i][j] = (s[i].u(j) xor keys[4 + i].u(j)).toByte()
            return s.flatMap { it.toList() }.toByteArray()
        }
        val src = data.copyOf()
        val pt = ByteArray(32)
        for (i in 0 until 31) {
            val at = i * 8
            var n = (src.u(at) shr 4) and 2
            n = n or (src.u(at + 1) and 64)
            n = n or ((src.u(at + 2) shr 2) and 1)
            n = n or ((src.u(at + 3) shl 3) and 128)
            n = n or ((src.u(at + 4) shr 1) and 4)
            n = n or ((src.u(at + 5) shl 3) and 16)
            n = n or ((src.u(at + 6) shl 5) and 32)
            pt[i] = (n or ((src.u(at + 7) shr 4) and 8)).toByte()
        }
        pt[31] = 1
        val blocks = ArrayList<Byte>()
        var prev = iv
        for (off in 0 until 32 step 16) {
            val vv = block(xorBytes(pt.copyOfRange(off, off + 16), prev)); blocks.addAll(vv.toList()); prev = vv
        }
        val bl = blocks.toByteArray()
        for (i in 0 until 31) {
            val at = i * 8; val k = bl.u(i)
            src[at] = ((src.u(at) and 0xdf) or ((k shl 4) and 32)).toByte()
            src[at + 1] = ((src.u(at + 1) and 0xbf) or (k and 64)).toByte()
            src[at + 2] = ((src.u(at + 2) and 0xfb) or ((k shl 2) and 4)).toByte()
            src[at + 3] = ((src.u(at + 3) and 0xef) or ((k shr 3) and 16)).toByte()
            src[at + 4] = ((src.u(at + 4) and 0xf7) or (((k + k) and 8))).toByte()
            src[at + 5] = ((src.u(at + 5) and 0xfd) or ((k shr 3) and 2)).toByte()
            src[at + 6] = ((src.u(at + 6) and 0xfe) or ((k shr 5) and 1)).toByte()
            src[at + 7] = ((src.u(at + 7) and 0x7f) or ((k shl 4) and 128)).toByte()
        }
        return byteArrayOf(bl[31]) + src
    }

    // medusa
    fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val n = data.size
        val e = ByteArray(n)
        for (i in 0 until n) {
            val at = (i * 4) and 28
            val d0 = key.u(at); val d1 = key.u(at + 1)
            val sw = ((data.u(i) shl 4) or (data.u(i) shr 4)) and 0xff
            var d2 = (sw + d0) and 0xff
            d2 = d2.inv() and 0xff xor d1
            d2 = ((d2 shl 3) or (d2 shr 5)) and 0xff
            d2 = (d2 + d1) and 0xff
            d2 = d2 xor d0
            e[n - i - 1] = d2.inv().toByte()
        }
        val last = e.u(n - 1) xor e.u(n - 2)
        val first = e.u(0)
        e[0] = ((last.inv() and 0xff) + first).toByte()
        e[1] = ((e.u(0) xor e.u(n - 1) xor 254) + e.u(1)).toByte()
        val shifted = ((e.u(1) shl 3) or (e.u(1) shr 5)) and 0xff
        val fix2 = ((last - first) and 0xff) xor (shifted xor 2)
        e[2] = (e.u(2) + fix2).toByte()
        for (i in 0 until n - 4) {
            val sh = ((e.u(i + 2) shl 3) or (e.u(i + 2) shr 5)) and 0xff
            val temp = sh xor e.u(i + 1) xor (i + 3)
            e[i + 3] = ((temp.inv() and 0xff) + e.u(i + 3)).toByte()
        }
        e[n - 1] = (e.u(n - 1) xor e.u(n - 2)).toByte()
        var sum = 0
        for (i in 1 until n) sum += e.u(i)
        e[0] = ((e.u(0) xor e.u(1)) + (sum and 0xff)).toByte()
        return e
    }

    fun keyHash(signKey: ByteArray, random: UInt): Pair<ByteArray, ByteArray> {
        val hash = sm3(signKey + le32(random) + signKey)
        val d1 = (random shr 16) and 255u
        var d2 = (d1 shl 11) or (random shr 24)
        d2 = d2 xor (d1 shr 5)
        d2 = d2 xor d1
        d2 = d2.inv()
        return hash to le32(d2)
    }

    fun deviceProto(deviceID: String): ByteArray {
        var o = ByteArray(0)
        o += pSInt(1u, 1); o += pSInt(2u, 2); o += pStr(3u, appID); o += pStr(4u, deviceID)
        o += pStr(5u, "Ai6svO3PyrwDOUSmO6ZcResxu"); o += pStr(6u, "!noperm!")
        o += pSInt(7u, -888888); o += pSInt(8u, -888888); o += pSInt(9u, 3); o += pSInt(10u, -888888)
        o += pStr(11u, "!notset!"); o += pStr(12u, "Asia/Shanghai,8"); o += pStr(13u, "zh_CN"); o += pSInt(14u, 4)
        o += pFloat(16u, 255.24993896484375f); o += pFloat(17u, 35.58599090576172f); o += pFloat(18u, 3.467449188232422f)
        o += pFloat(19u, 3.467449188232422f); o += pFloat(20u, 255.1754913330078f); o += pFloat(21u, 42.17544174194336f)
        o += pStr(22u, "16"); o += pSInt(23u, 41); o += pSInt(24u, 36)
        o += pSInt(25u, 1728388016635); o += pSInt(26u, 1728388016635); o += pSInt(27u, 1728388016635); o += pSInt(28u, 1728388016637)
        o += pSInt(29u, -1); o += pStr(30u, "25053RT47C"); o += pStr(31u, "Redmi"); o += pStr(32u, "25053RT47C"); o += pStr(33u, "25053RT47C")
        o += pStr(34u, "Xiaomi"); o += pStr(35u, "Redmi"); o += pStr(36u, "Redmi"); o += pSInt(38u, 31)
        return o
    }

    fun medusa(rawURL: String, body: ByteArray, khronos: UInt, deviceID: String, versionName: String, ent: Entropy): String {
        val bm = md5(body)
        val queryRaw = rawURL.substringAfter('?', "")
        val qs = sm3(queryRaw.toByteArray())
        val ts = le32(khronos)
        val qbt = hashF13(qs, bm, ts, khronos)
        val check = (qs.u(0).toUInt() and 63u shl 14) or 0x18000001u or (qbt.u(0).toUInt() and 63u shl 8)
        val nested = pSInt(1u, 111) + pSInt(2u, 10) + pSInt(3u, 694367) + pSInt(5u, 586952199)
        val nested13 = pSInt(1u, ent.nowSecs) + pSInt(2u, -2) + pSInt(4u, 200)
        var env = pSInt(1u, ent.envLaunch) + pSInt(2u, 146331399) + pSInt(3u, 146331396) + pSInt(5u, 7)
        env += pStr(6u, "v04.06.04.03-bugfix") + pSInt(7u, ent.envPID) + pBytes(12u, deviceProto(deviceID)) + pBytes(13u, nested13) + pStr(14u, versionName)
        val queryHash = sm3(queryRaw.toByteArray() + bm + "none".toByteArray())
        val fixed1 = hexBytes("f7e85ffad7d7dc3bd62ac87057cf6118"); val fixed2 = hexBytes("4001000000000000")
        var msg = pBytes(1u, fixed1) + pSInt(2u, 3) + pSInt(3u, ent.messageRandom.toLong()) + pStr(4u, appID) + pStr(5u, deviceID)
        msg += pStr(6u, channelID) + pStr(7u, versionName) + pStr(8u, "v04.06.04-ml-android") + pSInt(9u, 67503104) + pBytes(10u, fixed2)
        msg += pSInt(12u, khronos.toLong()) + pBytes(13u, qbt) + pBytes(14u, qs.copyOfRange(0, 6)) + pBytes(15u, nested)
        msg += pStr(16u, "AXYQOS6n2m60x1fVZHIrH3iol") + pSInt(17u, khronos.toLong()) + pBytes(19u, queryHash) + pStr(20u, "none")
        msg += pSInt(21u, 312) + pBytes(23u, env)
        msg += pStr(24u, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""")
        val (hash, seed) = keyHash(medusaSignKey, ent.random)
        var tr = (fixed2 + xmxor(msg, hash)).reversedArray()
        for (i in tr.indices) tr[i] = (tr.u(i) xor seed.u((i.inv()) and 3)).toByte()
        val packed = byteArrayOf(0x35) + le32(ent.packedRandom) + le32(check) + tr +
            byteArrayOf(((ent.random shr 16) and 255u).toByte(), (ent.random shr 24).toByte())
        val encrypted = aesEncrypt(packed, medusaAESIV, medusaAESKey, khronos)
        val version = hexBytes("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val out = ArrayList<Byte>()
        for (i in 0 until 5) out.addAll(le32(rdLE32(version, i * 4) xor khronos).toList())
        out.add((ent.random and 255u).toByte()); out.add(((ent.random shr 8) and 255u).toByte()); out.add(0); out.add(1)
        out.addAll(encrypted.toList())
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
