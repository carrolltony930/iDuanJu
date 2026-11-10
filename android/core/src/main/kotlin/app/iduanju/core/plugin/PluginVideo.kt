package app.iduanju.core.plugin

import app.iduanju.core.cms.CMSCategory
import app.iduanju.core.hongguo.*
import app.iduanju.core.util.ContentFilter
import app.iduanju.core.util.NetGuard
import app.iduanju.core.util.ProviderException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** iOS PluginOut — sanitising readers for plugin output. */
object PluginOut {
    val allowedHeaders = setOf("referer", "user-agent", "origin", "accept", "accept-language")
    fun s(v: Any?, max: Int = 300): String = when (v) { is String -> v; is Number -> v.toString(); else -> "" }.trim().take(max)
    fun url(v: Any?): String { val u = s(v, 2000); return if (u.startsWith("https://") || u.startsWith("http://")) u else "" }
    fun strings(v: Any?, max: Int = 30): List<String> { val a = v as? JSONArray ?: return emptyList(); return (0 until minOf(a.length(), max)).map { s(a.opt(it), 60) }.filter { it.isNotEmpty() } }
    fun headers(v: Any?): Map<String, String> { val o = v as? JSONObject ?: return emptyMap(); return o.keys().asSequence().filter { it.lowercase() in allowedHeaders }.associateWith { s(o.opt(it), 500) } }
    fun categories(v: Any?): List<CMSCategory> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until minOf(a.length(), 60)).mapNotNull { i -> val c = a.optJSONObject(i) ?: return@mapNotNull null
            val id = s(c.opt("id")); val name = s(c.opt("name"), 40)
            if (id.isEmpty() || name.isEmpty() || ContentFilter.isBlocked(listOf(name, id))) null else CMSCategory(id, name) }
    }
    fun objs(v: Any?, max: Int): List<JSONObject> { val a = v as? JSONArray ?: return emptyList(); return (0 until minOf(a.length(), max)).mapNotNull { a.optJSONObject(it) } }
}

/** iOS JSVideoProvider — maps idj1 video/shortdrama plugin output onto the app's series/episode/stream models. */
class PluginVideo(val inst: PluginInstance, private val extraDomains: List<String> = emptyList()) {
    val m get() = inst.bundle.manifest
    val pid get() = m.id
    private val media get() = DomainPolicy(m.mediaDomains + m.httpDomains + extraDomains)
    var categories: List<CMSCategory> = emptyList(); private set

    private fun cover(u: String): String { val h = runCatching { java.net.URI(u).host }.getOrNull() ?: return ""; return if (media.allows(h) && !NetGuard.isIPLiteral(h)) u else "" }

    private fun series(o: JSONObject): HGSeries? {
        val id = PluginOut.s(o.opt("id")); val title = PluginOut.s(o.opt("title"), 200)
        if (id.isEmpty() || title.isEmpty()) return null
        val tags = PluginOut.strings(o.opt("tags")) + PluginOut.strings(o.opt("badges"))
        if (ContentFilter.isBlocked(listOf(title) + tags)) return null
        return HGSeries(seriesId = sid(pid, id), title = title, cover = cover(PluginOut.url(o.opt("cover"))), subtitle = PluginOut.s(o.opt("remarks"), 40).ifEmpty { null },
            author = PluginOut.strings(o.opt("actors")).joinToString(" ").ifEmpty { null }, orientation = if (o.optString("orientation") == "portrait") "portrait" else null, tags = tags)
    }

    private fun page(v: Any?, p: Int): HGPage {
        val o = v as? JSONObject ?: JSONObject()
        val items = PluginOut.objs(o.opt("items"), 500).mapNotNull(::series)
        return HGPage(items, o.optBoolean("hasMore") && items.isNotEmpty(), p + 1)
    }

    /** categoryId "" = 推荐 (home). */
    fun list(categoryId: String, p: Int): HGPage {
        if (categoryId.isEmpty()) {
            if (p > 1) return HGPage(emptyList(), false)
            val r = inst.call("home", JSONObject()) as? JSONObject ?: JSONObject()
            categories = PluginOut.categories(r.opt("categories"))
            if ((r.optJSONArray("items")?.length() ?: 0) > 0) return page(r, 1)
            val first = categories.firstOrNull() ?: return HGPage(emptyList(), false)
            return page(inst.call("category", JSONObject().put("id", first.id).put("page", 1).put("filters", JSONObject())), 1)
        }
        return page(inst.call("category", JSONObject().put("id", categoryId).put("page", p).put("filters", JSONObject())), p)
    }

    fun search(q: String, p: Int): HGPage = page(inst.call("search", JSONObject().put("keyword", q).put("page", p)), p)

    fun detail(seriesSid: String): HGSeriesDetail {
        val id = parseSid(seriesSid)?.second ?: throw ProviderException.invalid("插件条目 id 无效")
        val o = inst.call("detail", JSONObject().put("id", id)) as? JSONObject ?: JSONObject()
        val title = PluginOut.s(o.opt("title"), 200)
        val tags = PluginOut.strings(o.opt("tags"))
        if (ContentFilter.isBlocked(listOf(title) + tags)) throw ProviderException.unsupported("该内容已被过滤")
        val line = PluginOut.objs(o.opt("lines"), 20).firstOrNull()
        val lineName = PluginOut.s(line?.opt("name"), 40)
        val eps = PluginOut.objs(line?.opt("episodes"), 3000).mapIndexedNotNull { i, e ->
            val eid = PluginOut.s(e.opt("id"), 2000); if (eid.isEmpty()) null
            else HGEpisode(i + 1, epVid(pid, id, eid), e.optInt("duration"), PluginOut.s(e.opt("name"), 80).ifEmpty { "第${i + 1}集" }, page = lineName)
        }.mapIndexed { i, e -> e.copy(ep = i + 1) }
        return HGSeriesDetail(seriesId = seriesSid, title = title, cover = cover(PluginOut.url(o.opt("cover"))), desc = PluginOut.s(o.opt("desc"), 4000),
            author = PluginOut.s(o.opt("director"), 80), actors = PluginOut.strings(o.opt("actors")), totalEps = eps.size, episodes = eps, tags = tags,
            episodeLabel = if (eps.size > 1) "共${eps.size}集" else "长视频", source = m.name)
    }

    fun resolve(ep: HGEpisode): HGResolvedVideo {
        val (_, sidRaw, eid) = parseEp(ep.vid) ?: throw ProviderException.invalid("插件剧集 id 无效")
        val o = inst.call("play", JSONObject().put("line", ep.page ?: "").put("episodeId", eid).put("id", sidRaw)) as? JSONObject ?: JSONObject()
        val pol = media
        val streams = PluginOut.objs(o.opt("streams"), 12).mapIndexedNotNull { i, st ->
            val u = PluginOut.url(st.opt("url"))
            val url = runCatching { java.net.URI(u) }.getOrNull(); val host = url?.host
            if (url == null || host == null || url.scheme != "https" || !pol.allows(host) || NetGuard.isIPLiteral(host)) {
                inst.host.log("✕ 丢弃未授权/非 https 的流地址 ${host ?: u.take(40)}"); return@mapIndexedNotNull null
            }
            val fmt = st.optString("format").ifEmpty { if (u.contains(".m3u8")) "hls" else "mp4" }
            val h = PluginOut.headers(st.opt("headers")).toMutableMap()
            if (h.keys.none { it.equals("user-agent", true) }) h["User-Agent"] = inst.host.learnedUA ?: PluginHost.DEFAULT_UA
            HGStream("p$i", PluginOut.s(st.opt("name"), 40).ifEmpty { host }, u, kind = if (fmt == "hls") HGStreamKind.hls else HGStreamKind.mp4Clear, headers = h)
        }
        val first = streams.firstOrNull() ?: throw ProviderException.upstream("插件没有返回可播放的地址（需 https 且在 media 白名单内）")
        return HGResolvedVideo(ep.vid, first.url, streams = streams, expiresAt = o.optLong("expiresAt"))
    }

    companion object {
        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        private fun unb64(s: String) = String(Base64.getUrlDecoder().decode(s))
        fun isPlugin(id: String) = id.startsWith("plugin:")
        fun sid(pid: String, id: String) = "plugin:$pid:${b64(id)}"
        fun parseSid(s: String): Pair<String, String>? { val p = s.split(":"); return if (p.size == 3 && p[0] == "plugin") runCatching { p[1] to unb64(p[2]) }.getOrNull() else null }
        fun epVid(pid: String, id: String, ep: String) = "plugin:$pid:${b64(id)}:${b64(ep)}"
        fun parseEp(s: String): Triple<String, String, String>? { val p = s.split(":"); return if (p.size == 4 && p[0] == "plugin") runCatching { Triple(p[1], unb64(p[2]), unb64(p[3])) }.getOrNull() else null }
        fun pluginId(s: String): String? = s.split(":").takeIf { it.size >= 3 && it[0] == "plugin" }?.get(1)
    }
}
