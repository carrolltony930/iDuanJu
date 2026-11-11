package app.iduanju.core.hongguo

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
* Signing for the pure playback endpoint (`/novel/player/video_model/v1/`), port of iOS
* `HGPlayerSign.playbackHeaders` (STV/Providers/Hongguo/HGPlayerSign.swift). Protocol constants
* cross-checked with hongguo-open-player 3f687c5 (src/server/native/argus.js, GPL-3.0-only).
*
* Signs the exact wire query + body with the 551-framed Argus and the matching Ladon. Legacy
* Gorgon / Medusa / Helios must NOT be added to this dialect: the upstream answers 200 with an
* empty body when they are present, even if Argus and Ladon are valid.
*/
object HGPlaybackSign {
    private const val LICENSE = "1611921764"

    fun headers(
        query: String, body: ByteArray, timestamp: UInt,
        argusRandom: UInt? = null, ladonRandom: UInt? = null,
    ): Map<String, String> {
        fun param(name: String): String {
            for (part in query.split("&")) {
                val pair = part.split("=", limit = 2)
                if (java.net.URLDecoder.decode(pair[0], "UTF-8") == name) {
                    return if (pair.size > 1) java.net.URLDecoder.decode(pair[1], "UTF-8") else ""
                }
            }
            return ""
        }
        val S = HGPlayerSign
        val aid = param("aid").ifEmpty { "1967" }
        val random = argusRandom ?: kotlin.random.Random.nextInt().toUInt()
        // This schema uses unsigned uint32 varints, unlike the older Medusa schema.
        fun uint(field: UInt, value: UInt): ByteArray = S.pKey(field, 0) + S.varint(value.toULong())
        fun str(field: UInt, value: String): ByteArray {
            val b = value.toByteArray()
            return S.pKey(field, 2) + S.varint(b.size.toULong()) + b
        }
        var bean = uint(1u, 1077940818u) + uint(2u, 2u) + uint(3u, random)
        bean += str(4u, aid) + str(5u, param("device_id")) + str(6u, LICENSE)
        bean += str(7u, param("version_name")) + str(8u, "v04.04.05-ov-android")
        bean += uint(9u, 134744640u) + S.pBytes(10u, ByteArray(8)) + uint(11u, 0u)
        bean += uint(12u, timestamp * 2u)
        bean += S.pBytes(13u, S.sm3(if (body.isEmpty()) ByteArray(16) else S.md5(body)).copyOf(6))
        bean += S.pBytes(14u, S.sm3(if (query.isEmpty()) ByteArray(16) else query.toByteArray()).copyOf(6))
        bean += S.pBytes(15u, uint(1u, 1u) + uint(2u, 1u) + uint(3u, 1u) + uint(7u, 0xC792ECCCu))
        bean += str(16u, param("iid")) + str(20u, "none") + uint(21u, 738u)
        bean += S.pBytes(23u, str(1u, "NX551J") + uint(2u, 8196u) + uint(4u, 0x80E0D800u)) + uint(25u, 2u)

        val signKey = S.hexBytes("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
        val keys = ArrayList<ULong>((0 until 4).map { S.rdLE64(signKey, it * 8) })
        val z = 0x3DC94C3A046D678BuL
        for (i in 4 until 72) {
            var t = S.ror64(keys[i - 1], 3uL) xor keys[i - 3]
            t = t xor S.ror64(t, 1uL)
            keys.add(keys[i - 4].inv() xor t xor ((z shr ((i - 4) % 62)) and 1uL) xor 3uL)
        }
        val padded = pad(bean)
        var encrypted = ByteArray(0)
        for (at in padded.indices step 16) {
            var left = S.rdLE64(padded, at); var right = S.rdLE64(padded, at + 8)
            for (key in keys) {
                val oldRight = right
                right = left xor (S.ror64(right, 63uL) and S.ror64(right, 56uL)) xor S.ror64(right, 62uL) xor key
                left = oldRight
            }
            encrypted += S.wrLE64(left) + S.wrLE64(right)
        }
        val magic = S.hexBytes("f2f7fcfff2f7fcff")
        val frame = magic + encrypted + S.hexBytes("eafb2cfe8568519154")
        for (i in 8 until frame.size) frame[i] = (frame[i].toInt() xor magic[i % 8].toInt()).toByte()
        frame.reverse()
        val packet = pad(frame + byteArrayOf(0x61, 0x6f))
        val argus = aesCbc(packet, S.hexBytes("d31e3718288a1027baab59f146a09a9c"), S.hexBytes("ea180a0336ed352fcd24e4d50018ae54"))
        val b64 = Base64.getEncoder()
        val h = linkedMapOf(
            "x-ss-req-ticket" to (timestamp.toULong() * 1000uL).toString(),
            "x-khronos" to timestamp.toString(),
            "x-argus" to b64.encodeToString(byteArrayOf(0xf2.toByte(), 0x81.toByte()) + argus),
            "x-ladon" to ladon(timestamp, aid, ladonRandom ?: kotlin.random.Random.nextInt().toUInt()),
        )
        if (body.isNotEmpty()) h["x-ss-stub"] = S.hexStr(S.md5(body)).uppercase()
        return h
    }

    private fun pad(b: ByteArray): ByteArray {
        val n = 16 - b.size % 16
        return b + ByteArray(n) { n.toByte() }
    }

    private fun ladon(timestamp: UInt, aid: String, random: UInt): String {
        val S = HGPlayerSign
        val ascii = S.hexStr(S.md5(S.le32(random) + aid.toByteArray())).toByteArray()
        val table = arrayListOf(S.rdLE64(ascii, 0))
        var a = table[0]; var b = S.rdLE64(ascii, 8)
        val queue = ArrayDeque(listOf(S.rdLE64(ascii, 16), S.rdLE64(ascii, 24)))
        for (i in 0uL until 34uL) {
            val x = (S.ror64(b, 8uL) + a) xor i
            queue.addLast(x)
            a = x xor S.ror64(a, 61uL)
            table.add(a)
            b = queue.removeFirst()
        }
        val text = pad("$timestamp-$LICENSE-$aid".toByteArray())
        var result = S.le32(random)
        for (at in text.indices step 16) {
            var left = S.rdLE64(text, at); var right = S.rdLE64(text, at + 8)
            for (key in table.take(34)) {
                right = key xor (left + S.ror64(right, 8uL))
                left = right xor S.ror64(left, 61uL)
            }
            result += S.wrLE64(left) + S.wrLE64(right)
        }
        return Base64.getEncoder().encodeToString(result)
    }

    /** AES-128-CBC, no padding (input is already padded), like CCCrypt with options 0. */
    private fun aesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }
}
