package app.iduanju.core

import app.iduanju.core.cms.CMSSite
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hls.HLSLocal
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** ./gradlew :core:test -Plive=1 -PcmsApi=https://…/api.php/provide/vod/ --tests '*MacCMSLive*' (site never committed). */
class MacCMSLiveTest {
    @Test fun listDetailResolveHls() {
        val api = System.getProperty("idj.cmsApi")
        assumeTrue(System.getProperty("idj.live") == "1" && !api.isNullOrBlank())
        val norm = MacCMS.normalize(api!!)
        val cats = MacCMS.fetchCategories(norm)
        println("[cms] categories=${cats.size} ${cats.take(6).map { it.name }}")
        val site = CMSSite(CMSSite.key(norm), "live", norm, categories = cats)
        val page = MacCMS.list(site, "", 1)
        println("[cms] list items=${page.items.size} hasMore=${page.hasMore} first=${page.items.firstOrNull()?.title}")
        assertTrue(page.items.isNotEmpty())
        val sr = MacCMS.search(site, "爱情", 1)
        println("[cms] search 爱情 items=${sr.items.size}")
        var ok = false
        for (s in page.items.take(5)) {
            val d = try { MacCMS.detail(site, s.seriesId) } catch (e: Exception) { println("[cms] detail ${s.title}: ${e.message}"); continue }
            val r = MacCMS.resolve(site, d.episodes.first())
            println("[cms] detail ${d.title} eps=${d.episodes.size} line=${d.episodes.first().page} streams=${r.streams.map { it.quality }}")
            val st = r.streams.first()
            if (st.url.contains(".m3u8")) {
                val prep = HLSLocal.prepare(st.url) { u -> okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(u).header("User-Agent", MacCMS.UA).build()).execute().use { it.body!!.string() } }
                val segs = prep.text.lines().count { it.startsWith("http") }
                println("[cms] hls variant=${prep.variantUrl.take(60)} segments=$segs adRemoved=${prep.removed} (${prep.removedSeconds}s)")
                assertTrue(segs > 0)
            }
            ok = true; break
        }
        assertTrue(ok)
    }
}
