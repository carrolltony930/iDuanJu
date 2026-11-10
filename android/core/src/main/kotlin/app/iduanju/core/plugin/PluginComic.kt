package app.iduanju.core.plugin

import app.iduanju.core.manga.*
import app.iduanju.core.util.ContentFilter
import app.iduanju.core.util.ProviderException
import org.json.JSONObject
import java.util.Base64

/**
 * iOS PluginMedia refs: plugin images are `idjimg://<plugin>/<b64url({"u","h","t"})>` so each fetch is checked against that
 * plugin's media allow-list, carries only allowed headers, and applies declared transforms (stripes / aes) natively.
 */
object PluginMedia {
    const val scheme = "idjimg://"
    data class Ref(val plugin: String, val url: String, val headers: Map<String, String>, val transform: JSONObject?)
    fun ref(plugin: String, url: String, headers: Map<String, String> = emptyMap(), transform: JSONObject? = null): String {
        if (url.isEmpty()) return ""
        val o = JSONObject().put("u", url)
        headers.filterKeys { it.lowercase() in PluginOut.allowedHeaders }.takeIf { it.isNotEmpty() }?.let { o.put("h", JSONObject(it)) }
        if (transform != null && transform.length() > 0) o.put("t", transform)
        return scheme + plugin + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }
    fun parse(ref: String): Ref? {
        if (!ref.startsWith(scheme)) return null
        val rest = ref.removePrefix(scheme); val i = rest.lastIndexOf('/'); if (i <= 0) return null
        val o = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(rest.substring(i + 1)))) }.getOrNull() ?: return null
        val u = o.optString("u").ifEmpty { return null }
        val h = o.optJSONObject("h")?.let { x -> x.keys().asSequence().associateWith { x.optString(it) } } ?: emptyMap()
        return Ref(rest.substring(0, i), u, h, o.optJSONObject("t"))
    }
    /** AES transform (stripes needs a bitmap and is done by the app). */
    fun aes(t: JSONObject, data: ByteArray): ByteArray {
        val enc = t.optString("keyEncoding", "utf8")
        val key = PluginHost.dec(t.optString("key"), enc); val iv = t.optString("iv").takeIf { it.isNotEmpty() }?.let { PluginHost.dec(it, enc) }
        val input = if (t.optString("encoding") == "base64") PluginHost.dec(String(data), "base64") else data
        return PluginHost.aes(false, t.optString("mode", "cbc"), key, iv, input, t.optString("padding", "pkcs7") != "none")
    }
    /** JMComic stripe plan (hect0x7/JMComic-Crawler-Python decode_and_save): (srcY, dstY, height) per band. */
    fun stripes(h: Int, num: Int): List<Triple<Int, Int, Int>> {
        if (num <= 1 || h < num) return emptyList()
        val over = h % num
        return (0 until num).map { i ->
            var move = h / num; val ySrc = h - move * (i + 1) - over; var yDst = move * i
            if (i == 0) move += over else yDst += over
            Triple(ySrc, yDst, move)
        }
    }
}

/** iOS JSComicProvider — idj1 comic plugin → MangaProvider (source id `p.<plugin id>`). */
class PluginComic(private val inst: PluginInstance, override val label: String, override val note: String) : MangaProvider {
    private val pid get() = inst.bundle.manifest.id
    override val source get() = "p.$pid"
    override val imageHeaders = emptyMap<String, String>()
    var cats: List<MangaCategory> = emptyList(); private set
    override val categories get() = listOf(MangaCategory(HOME, "推荐")) + cats
    companion object { const val HOME = "__home" }

    private fun item(o: JSONObject): MangaItem? {
        val id = PluginOut.s(o.opt("id")); val title = PluginOut.s(o.opt("title"), 200)
        if (id.isEmpty() || title.isEmpty()) return null
        val tags = PluginOut.strings(o.opt("tags")) + PluginOut.strings(o.opt("badges"))
        if (ContentFilter.isBlocked(listOf(title) + tags)) return null
        return MangaItem(source, id, title, PluginMedia.ref(pid, PluginOut.url(o.opt("cover")), PluginOut.headers(o.opt("coverHeaders"))),
            PluginOut.s(o.opt("author"), 80), tags, PluginOut.s(o.opt("remarks"), 40))
    }
    private fun page(v: Any?): MangaPage { val o = v as? JSONObject ?: JSONObject(); val items = PluginOut.objs(o.opt("items"), 500).mapNotNull(::item); return MangaPage(items, o.optBoolean("hasMore") && items.isNotEmpty()) }
    private fun call(fn: String, a: JSONObject) = try { inst.call(fn, a) } catch (e: PluginException) { throw ProviderException(e.friendly) }

    override fun list(c: MangaCategory, page: Int): MangaPage {
        if (c.id == HOME) {
            if (page > 1) return MangaPage(emptyList(), false)
            val r = call("home", JSONObject()) as? JSONObject ?: JSONObject()
            cats = PluginOut.categories(r.opt("categories")).map { MangaCategory(it.id, it.name) }
            if ((r.optJSONArray("items")?.length() ?: 0) > 0) return page(r)
            val first = cats.firstOrNull() ?: return MangaPage(emptyList(), false)
            return page(call("category", JSONObject().put("id", first.id).put("page", 1).put("filters", JSONObject())))
        }
        return page(call("category", JSONObject().put("id", c.id).put("page", page).put("filters", JSONObject())))
    }
    override fun search(q: String, page: Int) = page(call("search", JSONObject().put("keyword", q).put("page", page)))
    override fun detail(m: MangaItem): MangaDetail {
        val o = call("detail", JSONObject().put("id", m.mangaId)) as? JSONObject ?: JSONObject()
        val it = m.copy()
        PluginOut.s(o.opt("title"), 200).takeIf { s -> s.isNotEmpty() }?.let { s -> it.title = s }
        PluginOut.url(o.opt("cover")).takeIf { s -> s.isNotEmpty() }?.let { s -> it.cover = PluginMedia.ref(pid, s, PluginOut.headers(o.opt("coverHeaders"))) }
        PluginOut.strings(o.opt("tags")).takeIf { s -> s.isNotEmpty() }?.let { s -> it.tags = s }
        PluginOut.s(o.opt("author"), 80).takeIf { s -> s.isNotEmpty() }?.let { s -> it.author = s }
        if (ContentFilter.isBlocked(listOf(it.title) + it.tags)) throw ProviderException.invalid("该内容已被过滤")
        val chs = PluginOut.objs(o.opt("chapters"), 5000).mapIndexedNotNull { i, c -> val id = PluginOut.s(c.opt("id")); val n = PluginOut.s(c.opt("name"), 120); if (id.isEmpty()) null else id to n.ifEmpty { "第${i + 1}话" } }
            .mapIndexed { i, (id, n) -> MangaChapter(id, n, i) }
        val status = when (o.optString("status")) { "completed" -> "已完结"; "ongoing" -> "连载中"; else -> "" }
        return MangaDetail(it, PluginOut.s(o.opt("desc"), 4000), status, chs)
    }
    override fun pages(m: MangaItem, c: MangaChapter): List<String> {
        val o = call("pages", JSONObject().put("chapterId", c.chapterId).put("mangaId", m.mangaId)) as? JSONObject ?: JSONObject()
        val common = PluginOut.headers(o.opt("headers"))
        val imgs = PluginOut.objs(o.opt("images"), 2000).mapNotNull { i ->
            val u = PluginOut.url(i.opt("url")).ifEmpty { return@mapNotNull null }
            val t = i.optJSONObject("transform")?.takeIf { it.optString("type") in setOf("stripes", "aes") }
            PluginMedia.ref(pid, u, common + PluginOut.headers(i.opt("headers")), t)
        }
        if (imgs.isEmpty()) throw ProviderException.upstream("插件没有返回图片")
        return imgs
    }
}
