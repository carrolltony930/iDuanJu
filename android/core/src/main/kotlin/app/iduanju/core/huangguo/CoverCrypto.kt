package app.iduanju.core.huangguo

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 黄果 cover obfuscation (iOS CoverCrypto): AI CDN = whole body AES-128-CBC no padding; legacy CDN = first 100 bytes XOR. Public site constants. */
object CoverCrypto {
    enum class Kind { plain, aiAES, legacyXOR }
    private val aiKey = "f5d965df75336270".toByteArray()   // AI site crypto-worker.js media_key
    private val aiIV = "97b60394abc2fbe1".toByteArray()    // media_iv
    private val xorKey = "2019ysapp7527".toByteArray()     // legacy frontend imgKey

    fun kind(url: String): Kind {
        val l = url.lowercase()
        if (l.contains("pic.drleao.cn") || l.contains("drleao.")) return Kind.aiAES
        if (l.contains("lkkwip.cn") || (l.contains("/cf") && l.contains("/image/"))) return Kind.legacyXOR
        if (url.startsWith("cf") || url.startsWith("hc")) return Kind.legacyXOR
        return Kind.plain
    }

    fun decrypt(data: ByteArray, kind: Kind): ByteArray = when (kind) {
        Kind.plain -> data
        Kind.aiAES -> {
            require(data.size >= 16 && data.size % 16 == 0) { "AI cover length" }
            Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aiKey, "AES"), IvParameterSpec(aiIV)) }.doFinal(data)
        }
        Kind.legacyXOR -> data.copyOf().also { b -> for (i in 0 until minOf(100, b.size)) b[i] = (b[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte() }
    }
}
