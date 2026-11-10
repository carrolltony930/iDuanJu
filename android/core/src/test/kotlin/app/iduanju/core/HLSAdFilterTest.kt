package app.iduanju.core

import app.iduanju.core.hls.HLSAdFilter
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** 1:1 port of STVTests/HLSAdFilterTests.swift. */
class HLSAdFilterTest {
    private fun pl(body: List<String>, endlist: Boolean = true) =
        (listOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:10", "#EXT-X-MEDIA-SEQUENCE:0") + body + (if (endlist) listOf("#EXT-X-ENDLIST") else emptyList())).joinToString("\n")
    private fun segs(prefix: String, range: IntRange, dur: Double = 6.0) = range.flatMap { listOf("#EXTINF:${fmt(dur)},", "$prefix${"%04d".format(it)}.ts") }
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toInt().toString() + ".0" else d.toString()
    private fun uris(t: String) = t.split("\n").filter { !it.startsWith("#") && it.isNotEmpty() }
    private val base = URI("https://v.example.com/20250101/abc/1000kb/hls/index.m3u8")
    private fun count(t: String, s: String) = t.split(s).size - 1

    @Test fun baofengAdjump() {
        val text = pl(segs("main", 0..9) + "#EXT-X-DISCONTINUITY" + segs("https://bfzy.example/video/adjump/time/ad", 0..2, 5.0) + "#EXT-X-DISCONTINUITY" + segs("main", 10..19))
        val r = HLSAdFilter.filter(text, base)
        assertEquals(3, r.removed)
        assertFalse(r.text.contains("adjump"))
        assertEquals(20, uris(r.text).size)
        assertEquals(1, count(r.text, "#EXT-X-DISCONTINUITY"))
        assertTrue(r.text.endsWith("#EXT-X-ENDLIST\n"))
    }

    @Test fun clearBlockInsideAES() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"https://v.example.com/key.key\""
        val body = listOf(key) + segs("main", 0..14, 4.0) + listOf("#EXT-X-DISCONTINUITY", "#EXT-X-KEY:METHOD=NONE") + segs("main_ad", 0..3, 4.0) +
            listOf("#EXT-X-DISCONTINUITY", key) + segs("main", 15..30, 4.0)
        val r = HLSAdFilter.filter(pl(body), URI("https://play.360zyx.vip/x/index.m3u8"))
        assertEquals(4, r.removed)
        assertFalse(uris(r.text).any { it.contains("main_ad") })
        assertFalse(r.text.contains("METHOD=NONE"))
        assertEquals(1, count(r.text, "METHOD=AES-128"))
    }

    @Test fun aikunForeignHost() {
        val body = segs("main", 0..20) + "#EXT-X-DISCONTINUITY" + segs("https://c1.bfikuncdn.com/ad/seg", 0..3, 4.0) + "#EXT-X-DISCONTINUITY" + segs("main", 21..40)
        val r = HLSAdFilter.filter(pl(body), base)
        assertEquals(4, r.removed); assertFalse(r.text.contains("bfikuncdn"))
    }

    @Test fun yinghuaLeadingBlock() {
        val body = segs("https://ts1.yhzybf.com/ad/a", 0..2, 5.0) + "#EXT-X-DISCONTINUITY" + segs("main", 0..30)
        val r = HLSAdFilter.filter(pl(body), base)
        assertEquals(3, r.removed); assertFalse(r.text.contains("yhzybf")); assertFalse(r.text.contains("#EXT-X-DISCONTINUITY"))
        assertEquals("main0000.ts", uris(r.text).first())
    }

    @Test fun ruyiDiscontinuitiesKept() {
        val body = ArrayList<String>()
        for (b in 0 until 8) { if (b > 0) body.add("#EXT-X-DISCONTINUITY"); body += segs("main", b * 5..b * 5 + 4, 5.0) }
        val text = pl(body)
        val r = HLSAdFilter.filter(text, base)
        assertEquals(0, r.removed); assertEquals(text, r.text)
    }

    @Test fun repeatedSegments() {
        val ad = listOf("#EXTINF:3,", "https://v.example.com/20250101/abc/1000kb/hls/promo.ts")
        val r = HLSAdFilter.filter(pl(segs("main", 0..10) + ad + segs("main", 11..20) + ad + segs("main", 21..30)), base)
        assertEquals(2, r.removed); assertFalse(r.text.contains("promo.ts"))
    }

    @Test fun longOrTinyForeignBlocksKept() {
        val long = segs("main", 0..10) + "#EXT-X-DISCONTINUITY" + segs("https://other.example/p2/s", 0..9, 6.0) + "#EXT-X-DISCONTINUITY" + segs("main", 11..20)
        assertEquals(0, HLSAdFilter.filter(pl(long), base).removed)
        val tiny = segs("main", 0..10) + "#EXT-X-DISCONTINUITY" + segs("https://other.example/b/s", 0..0, 2.0) + "#EXT-X-DISCONTINUITY" + segs("main", 11..20)
        assertEquals(0, HLSAdFilter.filter(pl(tiny), base).removed)
    }

    @Test fun safetyCap() {
        val body = segs("main", 0..2) + "#EXT-X-DISCONTINUITY" + segs("https://ads.example/x/a", 0..3, 5.0)
        assertEquals(0, HLSAdFilter.filter(pl(body), base).removed)
    }

    @Test fun masterPlaylistUntouched() {
        val m = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n1000kb/hls/index.m3u8\n"
        assertEquals(m, HLSAdFilter.filter(m, base).text)
    }

    @Test fun toggle() {
        val text = pl(segs("main", 0..9) + "#EXT-X-DISCONTINUITY" + segs("https://bf.example/video/adjump/a", 0..1, 5.0) + "#EXT-X-DISCONTINUITY" + segs("main", 10..19))
        assertTrue(HLSAdFilter.enabled)
        assertFalse(HLSAdFilter.apply(text, base).contains("adjump"))
        HLSAdFilter.enabled = false
        try { assertEquals(text, HLSAdFilter.apply(text, base)) } finally { HLSAdFilter.enabled = true }
    }
}
