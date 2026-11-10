package app.iduanju.core.manga

import app.iduanju.core.huangguo.HGHtml
import app.iduanju.core.util.ContentFilter
import app.iduanju.core.util.ProviderException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** iOS MangaProviders: 包子漫画 (webmota mirrors) + MangaDex (safe/suggestive only). Comic plugins plug in via [MangaProvider]. */
data class MangaItem(val source: String, val mangaId: String, var title: String, var cover: String, var author: String = "", var tags: List<String> = emptyList(), var latest: String = "") {
    val id get() = "$source:$mangaId"
    fun json(): JSONObject = JSONObject().put("source", source).put("mangaId", mangaId).put("title", title).put("cover", cover).put("author", author).put("tags", JSONArray(tags)).put("latest", latest)
    companion object { fun from(o: JSONObject) = MangaItem(o.optString("source"), o.optString("mangaId"), o.optString("title"), o.optString("cover"), o.optString("author"),
        o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), o.optString("latest")) }
}
data class MangaChapter(val chapterId: String, val title: String, val index: Int)
data class MangaDetail(val item: MangaItem, val desc: String, val status: String, val chapters: List<MangaChapter>)
data class MangaCategory(val id: String, val title: String)
data class MangaPage(val items: List<MangaItem>, val hasMore: Boolean)

interface MangaProvider {
    val source: String
    val label: String
    val note: String
    val categories: List<MangaCategory>
    val imageHeaders: Map<String, String>
    fun list(c: MangaCategory, page: Int): MangaPage
    fun search(q: String, page: Int): MangaPage
    fun detail(m: MangaItem): MangaDetail
    fun pages(m: MangaItem, c: MangaChapter): List<String>
}

object MangaHTTP {
    const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile var fixture: ((String) -> String?)? = null
    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> {
        fixture?.invoke(url)?.let { return it to url }
        val b = Request.Builder().url(url).header("User-Agent", UA); headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (r.code !in 200..299) throw ProviderException.upstream("HTTP ${r.code} · ${r.request.url.host}")
            return (r.body?.string() ?: "") to r.request.url.toString()
        }
    }
    fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val (s, u) = get(url, headers)
        return runCatching { JSONObject(s) }.getOrElse { throw ProviderException.upstream("${java.net.URI(u).host} 返回非 JSON：${s.take(80)}") }
    }
}

private fun blocked(m: MangaItem) = ContentFilter.isBlocked(listOf(m.title) + m.tags)

class BaoziProvider : MangaProvider {
    companion object {
        val mirrors = listOf("https://cn.webmota.com", "https://www.webmota.com", "https://cn.baozimh.com", "https://www.baozimh.com")
        const val coverBase = "https://static-tw.baozimh.com/cover/"
        fun parseList(s: String): MangaPage {
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return MangaPage(emptyList(), false)
            val items = o.optJSONArray("items") ?: return MangaPage(emptyList(), false)
            val out = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { i ->
                val id = i.optString("comic_id").ifEmpty { return@mapNotNull null }; val name = i.optString("name").ifEmpty { return@mapNotNull null }
                MangaItem("baozi", id, name, coverBase + i.optString("topic_img").ifEmpty { "$id.jpg" }, i.optString("author"),
                    i.optJSONArray("type_names")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
            }
            return MangaPage(out.filterNot(::blocked), o.opt("next") is String && out.isNotEmpty())
        }
        fun parseCards(s: String): List<MangaItem> {
            val seen = HashSet<String>()
            return HGHtml.all("<a href=\"/comic/([^\"/]+)\" title=\"([^\"]*)\"[^>]*class=\"comics-card__poster[^\"]*\"[^>]*>\\s*<amp-img[^>]*src=\"([^\"]+)\"", s)
                .filter { seen.add(it[1]) }.map { MangaItem("baozi", it[1], HGHtml.unescape(it[2]), HGHtml.unescape(it[3])) }.filterNot(::blocked)
        }
        fun parseDetail(s: String, item: MangaItem): MangaDetail {
            val it = item.copy()
            HGHtml.classText(s, "comics-detail__title").takeIf { t -> t.isNotEmpty() }?.let { t -> it.title = t }
            HGHtml.classText(s, "comics-detail__author").takeIf { t -> t.isNotEmpty() }?.let { t -> it.author = t }
            val tags = HGHtml.groups("<div class=\"tag-list\"[^>]*>(.*?)</div>", s)?.let { g -> HGHtml.all("<span class=\"tag\"[^>]*>([^<]*)</span>", g[1]).map { m -> HGHtml.cleanText(m[1]) } } ?: emptyList()
            val status = tags.firstOrNull { t -> t.contains("连载") || t.contains("完结") } ?: ""
            it.tags = tags.filter { t -> t.isNotEmpty() && t != status }
            val seen = HashSet<String>(); val ch = ArrayList<Triple<Int, Int, String>>()
            for (c in HGHtml.all("comic_id=[^&\"]+&amp;section_slot=(\\d+)&amp;chapter_slot=(\\d+)\"[^>]*>\\s*<div[^>]*>\\s*<span[^>]*>([^<]*)</span>", s)) {
                val sec = c[1].toIntOrNull() ?: continue; val n = c[2].toIntOrNull() ?: continue
                if (seen.add("${sec}_$n")) ch.add(Triple(sec, n, HGHtml.cleanText(c[3])))
            }
            val list = ch.sortedWith(compareBy({ it.first }, { it.second })).mapIndexed { i, (a, b, t) -> MangaChapter("${a}_$b", t, i) }
            it.latest = list.lastOrNull()?.title ?: ""
            return MangaDetail(it, HGHtml.classText(s, "comics-detail__desc"), status, list)
        }
        fun parsePages(s: String): List<String> {
            val seen = LinkedHashSet<String>()
            for (m in HGHtml.all("<amp-img[^>]*id=\"chapter-img-[^\"]*\"[^>]*>", s)) {
                val u = HGHtml.attr(m[0], "data-src", "src"); if (u.isEmpty()) continue
                seen.add(Regex("^https?://[a-z0-9-]+\\.bzcdn\\.net/").replace(u, "https://static-tw.baozimh.com/"))
            }
            return seen.toList()
        }
    }
    override val source = "baozi"; override val label = "包子漫画"; override val note = "国漫/日漫/韩漫 · 中文 · 大陆直连（webmota 镜像）"
    override val imageHeaders = emptyMap<String, String>()
    override val categories = listOf("all|all" to "全部", "all|cn" to "国漫", "all|jp" to "日本", "all|kr" to "韩国", "lianai|all" to "恋爱", "rexie|all" to "热血",
        "xuanhuan|all" to "玄幻", "gaoxiao|all" to "搞笑", "kehuan|all" to "科幻", "xuanyi|all" to "悬疑", "gufeng|all" to "古风").map { MangaCategory(it.first, it.second) }
    private fun fetch(path: String): String {
        var last: Exception = ProviderException.upstream("包子漫画不可达")
        for (m in mirrors) try {
            val (s, final) = MangaHTTP.get(m + path)
            if (s.contains("challenge_required")) { last = ProviderException.upstream("${java.net.URI(final).host} 需要人机验证"); continue }
            return s
        } catch (e: Exception) { last = e }
        throw last
    }
    override fun list(c: MangaCategory, page: Int): MangaPage { val p = c.id.split("|"); return parseList(fetch("/api/bzmhq/amp_comic_list?type=${p[0]}&region=${p[1]}&state=all&filter=*&page=$page&limit=36&language=cn")) }
    override fun search(q: String, page: Int) = if (page != 1) MangaPage(emptyList(), false) else MangaPage(parseCards(fetch("/search?q=" + URLEncoder.encode(q, "UTF-8"))), false)
    override fun detail(m: MangaItem) = parseDetail(fetch("/comic/" + m.mangaId), m)
    override fun pages(m: MangaItem, c: MangaChapter): List<String> {
        val out = LinkedHashSet<String>(); var path = "/comic/chapter/${m.mangaId}/${c.chapterId}.html"
        for (n in 2..12) {
            val s = fetch(path); out.addAll(parsePages(s))
            if (!s.contains("/${m.mangaId}/${c.chapterId}_$n.html")) break
            path = "/comic/chapter/${m.mangaId}/${c.chapterId}_$n.html"
        }
        if (out.isEmpty()) throw ProviderException.upstream("本章没有图片（可能需要登录或已下架）")
        return out.toList()
    }
}

class MangaDexProvider : MangaProvider {
    companion object {
        const val api = "https://api.mangadex.org"
        const val ratings = "contentRating%5B%5D=safe&contentRating%5B%5D=suggestive"
        val langs = listOf("zh", "zh-hk", "en")
        /** MangaDex 302s browser UAs to a bot challenge; API clients identify themselves. */
        val headers = mapOf("User-Agent" to "iDuanJu-Android/1.5 (MangaDex API client)")
        private fun JSONObject.strMap(k: String): Map<String, String> = optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()
        fun title(a: JSONObject): String {
            val t = a.strMap("title"); val alts = HashMap<String, String>()
            a.optJSONArray("altTitles")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> o.keys().forEach { k -> alts.putIfAbsent(k, o.optString(k)) } } }
            for (k in listOf("zh", "zh-hk")) (t[k] ?: alts[k])?.let { return it }
            return t["en"] ?: t["ja-ro"] ?: t.values.firstOrNull() ?: alts["en"] ?: "?"
        }
        fun item(d: JSONObject): MangaItem? {
            val id = d.optString("id").ifEmpty { return null }; val a = d.optJSONObject("attributes") ?: return null
            val r = a.optString("contentRating"); if (r.isNotEmpty() && r != "safe" && r != "suggestive") return null
            val rels = d.optJSONArray("relationships")?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } } ?: emptyList()
            val cover = rels.firstOrNull { it.optString("type") == "cover_art" }?.optJSONObject("attributes")?.optString("fileName")?.takeIf { it.isNotEmpty() }
            val author = rels.firstOrNull { it.optString("type") == "author" }?.optJSONObject("attributes")?.optString("name") ?: ""
            val tags = a.optJSONArray("tags")?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it)?.optJSONObject("attributes")?.optJSONObject("name")?.optString("en")?.takeIf { s -> s.isNotEmpty() } } } ?: emptyList()
            val last = a.optString("lastChapter").let { if (it.isEmpty() || it == "null") "" else "第${it}话" }
            return MangaItem("mangadex", id, title(a), cover?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" } ?: "", author, tags.take(6), last)
        }
        fun parseList(o: JSONObject): MangaPage {
            val data = o.optJSONArray("data") ?: JSONArray()
            val items = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::item) }.filterNot(::blocked)
            return MangaPage(items, o.optInt("offset") + data.length() < o.optInt("total"))
        }
        fun chapters(feed: List<JSONObject>): List<MangaChapter> {
            data class B(val rank: Int, val id: String, val title: String, val num: Double)
            val best = LinkedHashMap<String, B>()
            for (c in feed) {
                val id = c.optString("id"); if (id.isEmpty()) continue; val a = c.optJSONObject("attributes") ?: continue
                if (a.optInt("pages") <= 0 || (a.opt("externalUrl").let { it != null && it != JSONObject.NULL })) continue
                val ch = a.optString("chapter").let { if (it == "null") "" else it }; val lang = a.optString("translatedLanguage")
                val rank = langs.indexOf(lang).let { if (it < 0) 9 else it }; val key = ch.ifEmpty { id }
                val t = a.optString("title").takeIf { it.isNotEmpty() && it != "null" }
                val title = (if (ch.isEmpty()) "单话" else "第${ch}话") + (t?.let { " $it" } ?: "") + (if (lang == "en") " (EN)" else "")
                val cur = best[key]; if (cur != null && cur.rank <= rank) continue
                best[key] = B(rank, id, title, ch.toDoubleOrNull() ?: Double.MAX_VALUE)
            }
            return best.values.sortedBy { it.num }.mapIndexed { i, b -> MangaChapter(b.id, b.title, i) }
        }
        fun parseAtHome(o: JSONObject): List<String> {
            val base = o.optString("baseUrl").ifEmpty { return emptyList() }; val ch = o.optJSONObject("chapter") ?: return emptyList(); val hash = ch.optString("hash")
            val ds = ch.optJSONArray("dataSaver"); if (ds != null && ds.length() > 0) return (0 until ds.length()).map { "$base/data-saver/$hash/${ds.optString(it)}" }
            val d = ch.optJSONArray("data") ?: return emptyList(); return (0 until d.length()).map { "$base/data/$hash/${d.optString(it)}" }
        }
    }
    override val source = "mangadex"; override val label = "MangaDex"; override val note = "官方公开 API · 仅全年龄/轻度分级 · 中文+英文章节"
    override val imageHeaders = headers
    override val categories = listOf("followedCount" to "热门", "latestUploadedChapter" to "最近更新", "createdAt" to "新作", "rating" to "高分").map { MangaCategory(it.first, it.second) }
    override fun list(c: MangaCategory, page: Int) = parseList(MangaHTTP.json("$api/manga?limit=30&offset=${(page - 1) * 30}&$ratings&includes%5B%5D=cover_art&includes%5B%5D=author&availableTranslatedLanguage%5B%5D=zh&availableTranslatedLanguage%5B%5D=zh-hk&order%5B${c.id}%5D=desc", headers))
    override fun search(q: String, page: Int) = parseList(MangaHTTP.json("$api/manga?limit=30&offset=${(page - 1) * 30}&title=${URLEncoder.encode(q, "UTF-8")}&$ratings&includes%5B%5D=cover_art&includes%5B%5D=author&order%5Brelevance%5D=desc", headers))
    override fun detail(m: MangaItem): MangaDetail {
        val o = MangaHTTP.json("$api/manga/${m.mangaId}?includes%5B%5D=cover_art&includes%5B%5D=author", headers)
        val d = o.optJSONObject("data"); val it = d?.let(::item) ?: m; if (it.cover.isEmpty()) it.cover = m.cover
        val a = d?.optJSONObject("attributes") ?: JSONObject()
        val desc = a.optJSONObject("description")?.let { x -> x.optString("zh").ifEmpty { x.optString("zh-hk") }.ifEmpty { x.optString("en") } } ?: ""
        val feed = ArrayList<JSONObject>(); var off = 0
        while (true) {
            val f = MangaHTTP.json("$api/manga/${m.mangaId}/feed?limit=500&offset=$off&" + langs.joinToString("&") { "translatedLanguage%5B%5D=$it" } + "&$ratings&order%5Bvolume%5D=asc&order%5Bchapter%5D=asc", headers)
            val arr = f.optJSONArray("data") ?: JSONArray(); for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(feed::add); off += arr.length()
            if (off >= f.optInt("total") || arr.length() == 0 || off >= 2000) break
        }
        return MangaDetail(it, desc, a.optString("status"), chapters(feed))
    }
    override fun pages(m: MangaItem, c: MangaChapter) = parseAtHome(MangaHTTP.json("$api/at-home/server/${c.chapterId}", headers))
}
