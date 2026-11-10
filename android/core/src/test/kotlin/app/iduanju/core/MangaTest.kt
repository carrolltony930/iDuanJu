package app.iduanju.core

import app.iduanju.core.manga.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Ported from iOS TelegramMangaTests (manga parts, same fixtures). */
class MangaTest {
    private fun fx(n: String) = File("../../STVTests/Fixtures/$n").readText()

    @Test fun baozi() {
        val l = BaoziProvider.parseList(fx("manga_baozi_list.json"))
        assertEquals(36, l.items.size); assertTrue(l.hasMore); assertTrue(l.items[0].cover.startsWith("https://static-tw.baozimh.com/cover/"))
        val s = BaoziProvider.parseCards(fx("manga_baozi_search.html"))
        assertTrue(s.size > 10); assertTrue(s.all { it.title.isNotEmpty() && it.cover.startsWith("https://") })
        val d = BaoziProvider.parseDetail(fx("manga_baozi_detail.html"), MangaItem("baozi", "yirenzhixia-dongmantang", "", ""))
        assertEquals("一人之下", d.item.title); assertEquals("动漫堂", d.item.author); assertEquals("连载中", d.status)
        assertTrue(d.chapters.size > 800)
        assertEquals("0_0", d.chapters.first().chapterId); assertEquals("1.姐姐1", d.chapters.first().title)
        assertEquals(d.chapters.size, d.chapters.map { it.chapterId }.toSet().size)
        assertEquals((0 until d.chapters.size).toList(), d.chapters.map { it.index })
        val pages = BaoziProvider.parsePages(fx("manga_baozi_chapter.html"))
        assertTrue(pages.size > 10); assertTrue(pages.all { it.startsWith("https://static-tw.baozimh.com/scomic/yirenzhixia-dongmantang/") }); assertTrue(pages[0].endsWith("/1.jpg"))
    }

    @Test fun mangadex() {
        val l = MangaDexProvider.parseList(JSONObject(fx("manga_md_list.json")))
        assertEquals(6, l.items.size); assertTrue(l.hasMore)
        assertTrue(l.items.all { it.cover.startsWith("https://uploads.mangadex.org/covers/") && it.cover.endsWith(".256.jpg") })
        val f = JSONObject(fx("manga_md_feed.json")).getJSONArray("data")
        val ch = MangaDexProvider.chapters((0 until f.length()).map { f.getJSONObject(it) })
        assertFalse(ch.isEmpty()); assertEquals((0 until ch.size).toList(), ch.map { it.index }); assertTrue(ch[0].title.startsWith("第75话"))
        val pages = MangaDexProvider.parseAtHome(JSONObject(fx("manga_md_athome.json")))
        assertEquals(15, pages.size); assertTrue(pages[0].contains("/data-saver/") || pages[0].contains("/data/"))
        assertNull(MangaDexProvider.item(JSONObject("""{"id":"x","attributes":{"contentRating":"erotica","title":{"en":"x"}}}""")))
    }

    @Test fun live() {
        assumeTrue(System.getProperty("idj.live") == "1")
        for (p in listOf(BaoziProvider(), MangaDexProvider())) {
            val r = runCatching {
                val l = p.list(p.categories[0], 1); val m = l.items.first()
                val d = p.detail(m); val pg = p.pages(d.item, d.chapters.first())
                "list=${l.items.size} '${m.title}' chapters=${d.chapters.size} pages=${pg.size}"
            }
            println("manga ${p.source} " + r.getOrElse { "ERR " + it.message })
        }
    }
}
