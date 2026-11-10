package app.iduanju.core

import app.iduanju.core.tg.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Ported from iOS TelegramMangaTests (same fixtures, STVTests/Fixtures). */
class TGParserTest {
    private fun fx(n: String) = File("../../STVTests/Fixtures/$n").readText()

    @Test fun telegram() {
        val p = TGParser.page(fx("tg_telegram.html"), "telegram")
        assertEquals(20, p.posts.size); assertEquals("Telegram News", p.info?.title); assertFalse(p.info!!.subscribers.isEmpty())
        assertEquals(441, p.before)
        assertEquals(p.posts.map { it.msgId }.sortedDescending(), p.posts.map { it.msgId })
        assertTrue(p.posts.all { it.date > 0 })
        val videos = p.posts.flatMap { it.media }.filter { it.kind == TGMedia.Kind.video }
        assertFalse(videos.isEmpty())
        assertTrue(videos.all { it.video?.startsWith("https://") == true && it.thumb.isNotEmpty() })
        assertTrue(videos.any { it.duration > 0 })
        assertTrue(p.posts.any { it.text.isNotEmpty() && !it.text.contains("<") })
    }

    @Test fun durov() {
        val p = TGParser.page(fx("tg_durov.html"), "durov")
        assertEquals(20, p.posts.size)
        assertTrue(p.posts.any { it.text.contains("Georgia") }); assertTrue(p.posts.any { it.preview != null }); assertTrue(p.posts.any { it.views.isNotEmpty() })
        val older = TGParser.page(fx("tg_durov_older.html"), "durov")
        val album = older.posts.firstOrNull { it.media.size > 1 }
        assertNotNull(album); assertTrue(album!!.media.all { !it.link.contains("?single") })
        assertTrue(older.posts.flatMap { it.media }.any { it.kind == TGMedia.Kind.photo })
        assertEquals("durov", TGChannel.normalize("https://t.me/s/durov?before=3")); assertEquals("telegram", TGChannel.normalize("@telegram"))
        assertNull(TGChannel.normalize("ab")); assertNull(TGChannel.normalize("频道名"))
    }

    @Test fun live() {
        assumeTrue(System.getProperty("idj.live") == "1")
        val p = TGClient().page("telegram")
        println("tg live posts=${p.posts.size} title=${p.info?.title} videos=${p.posts.sumOf { it.media.count { m -> m.video != null } }}")
        p.posts.take(6).forEach { println("tg post ${it.msgId} " + it.media.joinToString { m -> "${m.kind} thumb=${m.thumb.take(40)} v=${m.video?.take(40)}" }) }
        assertTrue(p.posts.isNotEmpty())
    }
}
