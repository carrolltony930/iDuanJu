package app.iduanju.core

import app.iduanju.core.tg.*
import org.junit.Assert.*
import org.junit.Test

class TGFilterTest {
    private fun post(text: String, links: List<String> = emptyList(), buttons: List<TGButton> = emptyList(), media: Boolean = false) =
        TGPost("news", 1, text = text, links = links, buttons = buttons, media = if (media) listOf(TGMedia(TGMedia.Kind.photo, "https://x/y.jpg")) else emptyList())

    @Test fun rules() {
        val p = post("Ｈｅｌｌｏ 世界 #每日", links = listOf("https://sub.ads.example.com/x"))
        assertTrue(TGRule(TGRule.Kind.keyword, "hello").matches(p))            // width + case folded
        assertTrue(TGRule(TGRule.Kind.domain, "*.example.com").matches(p))
        assertTrue(TGRule(TGRule.Kind.regex, "ads\\.example").matches(p))
        assertFalse(TGRule(TGRule.Kind.keyword, "hello", channel = "other").matches(p))
        assertFalse(TGRule(TGRule.Kind.keyword, "hello", enabled = false).matches(p))
        assertFalse(TGRule(TGRule.Kind.regex, "(").matches(p))
        assertEquals("规则 关键词：世界", TGFilter.verdict(p, listOf(TGRule(TGRule.Kind.keyword, "世界")), heuristics = false).reason)
    }

    @Test fun heuristics() {
        assertTrue(TGFilter.heuristic(post("#广告 新品上线")).blocked)
        assertTrue(TGFilter.heuristic(post("博彩 首充 送彩金")).blocked)
        assertTrue(TGFilter.heuristic(post("点击进群", links = listOf("https://t.me/+abcdef"))).blocked)
        assertFalse(TGFilter.heuristic(post("今天的新闻：天气晴", media = true)).blocked)
        // bot deep links deliver files, not ads
        assertFalse(TGFilter.heuristic(post("资源合集", buttons = listOf(TGButton("获取", "https://t.me/filesbot?start=abc")), media = true)).blocked)
        assertTrue(TGFilter.isOwn("https://t.me/news/12", "news"))
    }

    @Test fun importExport() {
        val b = TGFilter.importRules("// comment\nfoo\nre:ba+r\ndomain:example.com")!!
        assertEquals(listOf(TGRule.Kind.keyword, TGRule.Kind.regex, TGRule.Kind.domain), b.rules.map { it.kind })
        val back = TGFilter.importRules(TGFilter.export(b))!!
        assertEquals(b.rules.map { it.pattern }, back.rules.map { it.pattern })
        assertNull(TGFilter.importRules("  \n"))
        val sug = TGFilter.suggestions(post("优惠活动来啦 #福利", links = listOf("https://shop.example.com/a"), buttons = listOf(TGButton("立即购买", "https://shop.example.com/b"))))
        assertTrue(sug.any { it.kind == TGRule.Kind.domain && it.pattern == "shop.example.com" })
        assertTrue(sug.any { it.pattern == "#福利" })
    }
}
