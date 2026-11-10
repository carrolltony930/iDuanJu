package app.iduanju.core

import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGStreamKind
import app.iduanju.core.util.ContentFilter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 1:1 with iOS PluginTests.testMacCMSParsing / testMinorsFilter. */
class MacCMSTest {
    private val cmsList = JSONObject("""{"code":1,"page":1,"pagecount":3,"total":60,
        "class":[{"type_id":1,"type_name":"电影"},{"type_id":2,"type_name":"连续剧"},{"type_id":9,"type_name":"萝莉"}],
        "list":[{"vod_id":101,"vod_name":"示例电影","vod_pic":"http://img.example.com/a.jpg","vod_remarks":"HD","type_name":"电影","vod_actor":"甲,乙"},
                {"vod_id":102,"vod_name":"不该出现","vod_pic":"","type_name":"萝莉","vod_remarks":""}]}""")

    @Test fun parsing() {
        assertEquals(listOf("电影", "连续剧"), MacCMS.parseCategories(cmsList).map { it.name })
        val page = MacCMS.page(cmsList, "abc", 1)
        assertEquals(listOf("cms:abc:101"), page.items.map { it.seriesId })
        assertEquals("https://img.example.com/a.jpg", page.items.first().cover)
        assertTrue(page.hasMore)
        val detail = JSONObject().put("list", org.json.JSONArray().put(JSONObject()
            .put("vod_id", 7).put("vod_name", "剧").put("vod_pic", "https://x/a.jpg").put("type_name", "连续剧").put("vod_content", "<p>简介&nbsp;</p>")
            .put("vod_play_from", "share$$\$m3u8a$$\$mp4b")
            .put("vod_play_url", "第1集\$https://share.example.com/p/1#第2集\$https://share.example.com/p/2$$\$第1集\$https://cdn.example.com/1/index.m3u8#第2集\$https://cdn.example.com/2/index.m3u8$$\$第1集\$http://v.example.com/1.mp4")))
        val d = MacCMS.parseDetail(detail, HGSeries(seriesId = "cms:abc:7"))
        assertEquals("longest direct line wins", 2, d.episodes.size)
        assertEquals("m3u8a", d.episodes.first().page)
        val st = MacCMS.streams(d.episodes[0])
        assertEquals(listOf("https://cdn.example.com/1/index.m3u8", "https://v.example.com/1.mp4"), st.map { it.url })
        assertEquals(HGStreamKind.hls, st.first().kind)
        val lines = MacCMS.lines("a$$\$b", "x\$u1#y\$u2$$\$z\$u3")
        assertEquals(listOf("a", "b"), lines.map { it.name })
        assertEquals(listOf("u1", "u2"), lines[0].eps.map { it.second })
        assertThrows(Exception::class.java) { MacCMS.normalize("http://127.0.0.1/api.php") }
        assertEquals("https://cms.example.com/api.php/provide/vod/", MacCMS.normalize("http://cms.example.com/api.php/provide/vod/?ac=list"))
        assertEquals("abc" to "7", MacCMS.parseSid("cms:abc:7#1"))
    }

    @Test fun minorsFilter() {
        for (b in listOf(listOf("Loli Collection"), listOf("x", "lolicon"), listOf("正太"), listOf("萝莉"), listOf("蘿莉"), listOf("Schoolgirl"), listOf("school-girl"),
            listOf("age_regression"), listOf("未成年"), listOf("女子高生"), listOf("JK"), listOf("ロリ"), listOf("Hasunosora Jogakuin"), listOf("little girl")))
            assertTrue("$b", ContentFilter.isBlocked(b))
        for (ok in listOf("Secret Class", "MILF", "Mature", "Adults Return", "Majority Report", "Jake", "Japanese", "school life", "女大學生"))
            assertFalse(ok, ContentFilter.isBlocked(listOf(ok)))
    }
}
