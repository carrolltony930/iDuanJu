package app.iduanju.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Byte helpers. Byte buffers are IntArray-free: we use ByteArray and read bytes as 0..255 via [u]. */
object Bytes {
    private val rng = SecureRandom()
    fun hex(b: ByteArray): String = buildString(b.size * 2) { for (x in b) append(String.format("%02x", x.toInt() and 0xff)) }
    fun fromHex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val v = s.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = v.toByte()
        }
        return out
    }
    fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
    fun md5(s: String): ByteArray = md5(s.toByteArray(Charsets.UTF_8))
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)
    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun randInt(from: Int, untilExclusive: Int): Int = from + rng.nextInt(untilExclusive - from)
    fun u32LE(v: UInt): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun u32BE(v: UInt): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun readU64LE(b: ByteArray, o: Int): ULong { var v = 0UL; for (i in 0 until 8) v = v or ((b[o + i].toULong() and 0xffUL) shl (8 * i)); return v }
    fun writeU64LE(v: ULong, b: ByteArray, o: Int) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    /** Tolerant base64 (url-safe, missing padding). */
    fun b64decode(s: String): ByteArray? {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return try { Base64.getDecoder().decode(t) } catch (_: Exception) { null }
    }
}

fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xff

/** Go-compatible URL escaping (net/url.QueryEscape / url.Values.Encode), required for byte-exact signatures. */
object GoURL {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    fun queryEscape(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
                UNRESERVED.indexOf(c.toChar()) >= 0 && c < 128 -> append(c.toChar())
                c == 0x20 -> append('+')
                else -> append(String.format("%%%02X", c))
            }
        }
    }
    fun encode(values: Map<String, String>): String =
        values.keys.sorted().joinToString("&") { queryEscape(it) + "=" + queryEscape(values[it]!!) }
}

class ProviderException(message: String) : Exception(message) {
    companion object {
        fun upstream(m: String) = ProviderException("上游错误：$m")
        fun invalid(m: String) = ProviderException("参数错误：$m")
        fun unsupported(m: String) = ProviderException(m)
    }
}
