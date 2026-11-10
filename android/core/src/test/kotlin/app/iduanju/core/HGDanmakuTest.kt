package app.iduanju.core

import app.iduanju.core.hongguo.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HGDanmakuTest {
    @Test fun parseMergeText() {
        val p = HGDanmakuClient.parsePage(JSONObject("""{"code":0,"data":{"extra":{"next_query_danmaku_list_time":"60000"},"common_list_info":{"has_more":true},
          "data_list":[{"comment":{"common":{"group_id":"7","status":1,"content":{"text":"哈哈哈哈哈[笑哭]","color":16711680}},"expand":{"offset_time":1500},"stat":{"digg_count":3}}},
                       {"comment":{"common":{"group_id":"7","status":1,"content":{"text":"哈哈哈哈哈哈"}},"expand":{"offset_time":2500}},"mode":"top"},
                       {"comment":{"common":{"group_id":"8","status":1,"content":{"text":"other"}},"expand":{"offset_time":100}}}]}}"""), "7")
        assertEquals(60000L, p.nextMs); assertEquals(2, p.items.size)
        assertEquals("#FF0000", p.items[0].color); assertEquals(HGDanmaku.Mode.top, p.items[1].mode)
        assertEquals("哈哈哈哈哈😂", DanmakuText.display(p.items[0].text))
        val m = DanmakuMerger.merge(p.items)
        assertEquals(1, m.size); assertEquals(2, m[0].count)
        assertEquals("#ABCDEF", HGDanmakuClient.normalizeColor("#abcdef")); assertEquals("#FFFFFF", HGDanmakuClient.normalizeColor("#zz"))
        val h = HGCommentSign.headers("a=1", 1700000000, ByteArray(11) { it.toByte() })
        assertEquals(52, h["X-Gorgon"]!!.length); assertTrue(h["X-Argus"]!!.isNotEmpty()); assertTrue(h["X-Ladon"]!!.isNotEmpty())
    }

    /** -Plive=1 : real signed request for a hot series' first episode. */
    @Test fun cleanControlDropsObjectReplacement() {
        assertEquals("真的帅啊", HGDanmakuClient.cleanControl("真的帅啊\uFFFC"))
        assertEquals("a b", HGDanmakuClient.cleanControl("\uFEFFa\nb"))
    }

    @Test fun live() {
        assumeTrue(System.getProperty("idj.live") == "1")
        val hg = HongguoClient(File("build/hg-live-data").also { it.mkdirs() })
        val s = hg.rank(HGRankSub.hot, HGRankGenre.all).items.first()
        val d = hg.detail(s.seriesId, web = false)
        val c = HGDanmakuClient { HGCommentSign.newDeviceId() to HGCommentSign.newDeviceId() }
        val r = c.fetch(s.seriesId, d.episodes.first().vid)
        println("danmaku ${s.title} ep1 items=${r.items.size} truncated=${r.truncated} err='${r.error}' last=${c.lastError} sample=${r.items.take(3).map { "${it.time}s ${it.text}" }}")
        assertTrue(r.error.isEmpty())
    }
}
