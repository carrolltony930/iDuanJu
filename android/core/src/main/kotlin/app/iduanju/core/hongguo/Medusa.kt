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

    /** Placeholder: signed search needs tables.bin. Rank / detail / resolvePure do not. */
    fun buildPlainPB(rawURL: String, unix: Long, entropy: ByteArray): ByteArray {
        throw ProviderException.invalid("Medusa tables not loaded — signed search unavailable in this build stage")
    }

    fun mint(headTS: Long, pb: ByteArray): ByteArray {
        throw ProviderException.invalid("Medusa tables not loaded — signed search unavailable in this build stage")
    }
}
