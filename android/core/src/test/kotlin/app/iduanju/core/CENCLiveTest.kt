package app.iduanju.core

import app.iduanju.core.cenc.CENCDecryptor
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HongguoClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class CENCLiveTest {
    @Before fun setUp() {
        assumeTrue(System.getProperty("idj.live") == "1")
        System.getProperty("idj.hgDevice")?.takeIf { it.contains(",") }?.let { s ->
            val p = s.split(",", limit = 2)
            HGPlayerDevice.overrideProvider = { p[0].trim() to p[1].trim() }
        }
    }

    @Test fun downloadDecrypt() {
        val client = HongguoClient(File("build/hg-live-data").also { it.mkdirs() })
        val page = client.rank(HGRankSub.hot, HGRankGenre.all)
        val detail = client.detail(page.items.first().seriesId, web = false)
        val r = client.resolve(detail.episodes.first().vid)
        val s = r.streams.first()
        assertTrue(s.key.isNotEmpty())
        val conn = URI(s.url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "com.phoenix.read/73932")
        // download first 4MB (enough for moov + some samples) then full if small
        val bytes = conn.inputStream.use { it.readBytes() }
        println("[cenc] downloaded ${bytes.size} encrypted=${s.key.isNotEmpty()} head=${bytes.take(8).joinToString(""){ "%02x".format(it) }}")
        // ftyp check
        assertTrue(String(bytes, 4, 4) == "ftyp" || String(bytes, 4, 4) == "moof")
        try {
            val out = CENCDecryptor.decryptCopy(bytes, s.key)
            val outFile = File("build/hg-live-data/sample-dec.mp4")
            outFile.writeBytes(out)
            println("[cenc] decrypted -> ${outFile.absolutePath} size=${out.size}")
            // After decrypt, encv should become original format somewhere; at least file shouldn't throw
            assertTrue(out.size == bytes.size)
        } catch (e: Exception) {
            System.err.println("[cenc] decrypt failed: ${e.message}")
            // Still write encrypted for inspection
            File("build/hg-live-data/sample-enc.mp4").writeBytes(bytes)
            throw e
        }
    }
}
