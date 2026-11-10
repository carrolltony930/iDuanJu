package app.iduanju.core

import app.iduanju.core.huangguo.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Ported from iOS CoverMappingTests / SiteParsingTests (huangguo parts) + offline crypto round trips. */
class HuangguoTest {
    @Test fun covers() {
        val page = "https://00b4b.adihsppe.cc/video/1/"
        val h = """<meta property="og:image" content="https://expose.eisees.com/x.jpg"><script>{"coverSrc":"https:\/\/pic.drleao.cn\/upload_01\/upload\/20261107\/a.jpeg?auth_key=1-0-0-ab"}</script>"""
        val c = HuangguoClient.aiDetailCover(h, page, "")
        assertEquals("https://pic.drleao.cn/upload_01/upload/20261107/a.jpeg?auth_key=1-0-0-ab", c)
        assertEquals(CoverCrypto.Kind.aiAES, CoverCrypto.kind(c))
        val onlyOG = """<meta property="og:image" content="https://expose.eisees.com/x.jpg">"""
        assertEquals("https://pic.drleao.cn/list.jpeg", HuangguoClient.aiDetailCover(onlyOG, page, "https://pic.drleao.cn/list.jpeg"))
        assertEquals("", HuangguoClient.aiDetailCover(onlyOG, page, ""))
        assertEquals("https://00b4b.adihsppe.cc/static/p/1.jpg", HuangguoClient.aiDetailCover("""{"posterSrc":"/static/p/1.jpg"}""", page, ""))
        assertEquals("https://zzzznnn.lkkwip.cn/cf230705/image/d3/x.jpg", HuangguoClient.absCover("cf230705/image/d3/x.jpg", "https://zzzznnn.lkkwip.cn/"))
        assertEquals("https://a.example.com/x.jpg", HuangguoClient.absCover("http://a.example.com/x.jpg", "https://cdn"))
        assertEquals("https://a.example.com/x.jpg", HuangguoClient.absCover("//a.example.com/x.jpg", "https://cdn"))
        assertEquals(CoverCrypto.Kind.legacyXOR, CoverCrypto.kind("https://zzzznnn.lkkwip.cn/cf230705/image/d3/x.jpg"))
        val og = """<meta property="og:image" content="/uploads/lib/series/1987/cover.webp?v=1">"""
        assertEquals("https://huangguo.video/uploads/lib/series/1987/cover.webp?v=1", HGHtml.resolve("https://huangguo.video/series/s5wm9tsc", HGHtml.ogImage(og)))
    }

    @Test fun idsAndFilters() {
        val s = HuangguoClient.sid(HuangguoSub.video, "series/abc")
        assertFalse(s.contains("/"))
        assertEquals(Triple(HuangguoSub.video, "series/abc", null), HuangguoClient.split(s))
        assertEquals(Triple(HuangguoSub.ai, "123", "4"), HuangguoClient.split("huangguo:ai:123:4"))
        assertTrue(HuangguoClient.blocked("被迫的夜晚")); assertTrue(HuangguoClient.blocked("萝莉")); assertFalse(HuangguoClient.blocked("霸道总裁"))
        assertEquals("""{"devID":"x","isAppStore":false,"sysType":"ios"}""", HuangguoClient.encodeSorted(mapOf("sysType" to "ios", "devID" to "x", "isAppStore" to false)))
        val pb = HuangguoClient.parsePlayback("""{"status":1,"data":{"manifest_method":"GET","id":"12","ep":3,"streams":{"main":"https://c.example.com/a.m3u8"},"cdn":{"m3u8Enabled":true,"authKey":"x-cdn-auth","authValue":"v1","app":"a"}}}""", "12", 3)
        assertEquals("https://c.example.com/a.m3u8", pb.main); assertEquals(mapOf("x-cdn-auth" to "v1", "x-cdn-app" to "a"), pb.cdn)
    }

    @Test fun parsers() {
        val c = HuangguoClient()
        val ai = """<div class="hg-drama-card" data-track-id="77" data-track-title="霸道总裁"><img data-src="https://pic.drleao.cn/a.jpg"><span class="hg-drama-card__episode">全20集</span></div>"""
        val items = c.parseAICards(ai, "https://m.example.com/recommend/1/", "https://m.example.com")
        assertEquals(1, items.size); assertEquals("霸道总裁", items[0].title); assertEquals(20, items[0].episodeCount); assertEquals("huangguo:ai:77", items[0].seriesId)
        val eps = c.parseAIEpisodes("""<a class="hg-web-play__ep" data-ep-id="1" href="/video/77/">1</a><a class="hg-web-play__ep" data-ep-id="2" href="/video/77/ep-2/">2</a><a href="/video/99/">x</a>""", "https://m.example.com/video/77/", "77")
        assertEquals(listOf(1, 2), eps.map { it.ep }); assertEquals("第2集", eps[1].title)
        val v = """<article class="video-card group"><a href="/series/s75" class="x"><img src="/uploads/c.webp" alt="某剧"></a></article><article class="video-card"><a href="/video/v1"><img alt="第2集" src="/u.webp"></a><div data-hls="/hls/v1.m3u8"></div></article>"""
        val cards = c.parseVideoCards(v, "https://huangguo.video/videos")
        assertEquals(listOf("huangguo:video:series~s75", "huangguo:video:video~v1"), cards.map { it.seriesId })
        assertEquals("https://huangguo.video/uploads/c.webp", cards[0].cover)
        val veps = c.parseVideoEpisodes(v, "https://huangguo.video/series/s75")
        assertEquals(1, veps.size); assertEquals(2, veps[0].ep); assertEquals("https://huangguo.video/hls/v1.m3u8", veps[0].media)
    }

    @Test fun crypto() {
        val key = "k".repeat(16).toByteArray(); val iv = "i".repeat(16).toByteArray()
        val enc = HuangguoClient.aesCBC(true, "hello".toByteArray(), key, iv)
        assertEquals("hello", String(HuangguoClient.aesCBC(false, enc, key, iv)))
        val x = ByteArray(120) { it.toByte() }
        assertArrayEquals(x, CoverCrypto.decrypt(CoverCrypto.decrypt(x, CoverCrypto.Kind.legacyXOR), CoverCrypto.Kind.legacyXOR))
    }

    /** Live: counts only (adult source — titles are not printed). */
    @Test fun live() {
        assumeTrue(System.getProperty("idj.live") == "1")
        val c = HuangguoClient()
        val v = runCatching { c.list(HuangguoClient.videoChannels[0], 1) }
        println("hg video list=" + v.map { it.items.size }.getOrElse { "ERR " + it.message })
        v.getOrNull()?.items?.firstOrNull()?.let { s ->
            val d = runCatching { c.detail(s.seriesId, s.title, s.cover) }
            println("hg video detail eps=" + d.map { it.episodes.size }.getOrElse { "ERR " + it.message })
            d.getOrNull()?.episodes?.firstOrNull()?.let { e -> println("hg video resolve=" + runCatching { c.resolve(e).streams.first().let { it.kind.name + " " + it.url.substringBefore('?').takeLast(12) } }.getOrElse { "ERR " + it.message }) }
        }
        val a = runCatching { c.list(HuangguoClient.aiChannels[0], 1) }
        println("hg ai list=" + a.map { it.items.size }.getOrElse { "ERR " + it.message })
        a.getOrNull()?.items?.firstOrNull()?.let { s ->
            val d = runCatching { c.detail(s.seriesId, s.title, s.cover) }
            println("hg ai detail eps=" + d.map { it.episodes.size }.getOrElse { "ERR " + it.message })
            d.getOrNull()?.episodes?.firstOrNull()?.let { e -> println("hg ai resolve=" + runCatching { c.resolve(e).streams.first().let { it.kind.name + " hdr=" + it.headers.keys } }.getOrElse { "ERR " + it.message }) }
        }
        val l = runCatching { c.legacyChannels() }
        println("hg legacy tabs=" + l.map { it.size }.getOrElse { "ERR " + it.message })
        l.getOrNull()?.firstOrNull()?.let { ch -> println("hg legacy list=" + runCatching { c.list(ch, 1).items.size }.getOrElse { "ERR " + it.message }) }
    }
}
