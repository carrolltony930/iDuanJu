package app.iduanju.core

import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HongguoClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Live network tests against 红果. Run with: ./gradlew :core:test -Plive=1 [-PhgDevice=did,iid]
 * Soft-reject evidence is printed to stderr (HTTP code + empty body).
 */
class HongguoLiveTest {
    private lateinit var client: HongguoClient

    @Before
    fun setUp() {
        assumeTrue("set -Plive=1 to run", System.getProperty("idj.live") == "1")
        val dir = File("build/hg-live-data").also { it.mkdirs() }
        System.getProperty("idj.hgDevice")?.takeIf { it.contains(",") }?.let { s ->
            val p = s.split(",", limit = 2)
            HGPlayerDevice.overrideProvider = { p[0].trim() to p[1].trim() }
            println("[live] using override player device did=${p[0].trim().take(4)}…")
        }
        client = HongguoClient(dir)
    }

    @Test
    fun deviceRegisterAndRank() {
        val d = client.ensureDevice()
        println("[live] device_register did=${d.deviceId} iid=${d.iid}")
        assertTrue(d.deviceId.isNotEmpty())
        val page = client.rank(HGRankSub.hot, HGRankGenre.all)
        println("[live] rank items=${page.items.size} first=${page.items.firstOrNull()?.title}")
        assertTrue("rank empty", page.items.isNotEmpty())
    }

    @Test
    fun detailAndResolve() {
        val page = client.rank(HGRankSub.hot, HGRankGenre.all)
        assumeTrue(page.items.isNotEmpty())
        val sid = page.items.first().seriesId
        println("[live] detail sid=$sid title=${page.items.first().title}")
        val detail = client.detail(sid, web = false)
        println("[live] detail eps=${detail.episodes.size} title=${detail.title}")
        assertTrue("no episodes", detail.episodes.isNotEmpty())
        val ep = detail.episodes.first()
        println("[live] resolve vid=${ep.vid}")
        try {
            val r = client.resolve(ep.vid)
            println("[live] resolve ok streams=${r.streams.size} url=${r.url?.take(60)} key=${r.key != null} err=${r.err}")
            assertTrue(r.streams.isNotEmpty() || r.url != null)
            // Probe first bytes of media
            r.url?.let { u ->
            }
            r.streams.firstOrNull()?.let { s ->
                val conn = java.net.URI(s.url).toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "com.phoenix.read/73932")
                conn.connect()
                val code = conn.responseCode
                val n = conn.inputStream.use { it.read(ByteArray(64 * 1024)) }
                println("[live] media probe HTTP $code bytes=$n encrypted=${s.key.isNotEmpty()}")
                assertTrue("media HTTP $code", code in 200..299)
                assertTrue("media empty", n > 0)
            }
        } catch (e: Exception) {
            System.err.println("[live] RESOLVE FAILED: ${e.message}")
            throw e
        }
    }
}
