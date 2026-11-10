package app.iduanju.core.cms

import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGPage
import app.iduanju.core.hongguo.HGResolvedVideo
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.hongguo.HGStream
import app.iduanju.core.hongguo.HGStreamKind
import app.iduanju.core.util.ContentFilter
import app.iduanju.core.util.NetGuard
import app.iduanju.core.util.ProviderException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CMSCategory(val id: String, val name: String)

/** 苹果 CMS (maccms v10) 采集接口 site (iOS CMSSite). */
data class CMSSite(
    val key: String, val name: String, val api: String,
    val adult: Boolean = false, val enabled: Boolean = true,
    val categories: List<CMSCategory> = emptyList(), val origin: String = "manual",
) {
    fun toJSON(): JSONObject = JSONObject().put("key", key).put("name", name).put("api", api).put("adult", adult).put("enabled", enabled)
        .put("origin", origin).put("categories", JSONArray(categories.map { JSONObject().put("id", it.id).put("name", it.name) }))

    companion object {
        fun key(api: String): String = MessageDigest.getInstance("MD5").digest(api.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
        fun fromJSON(o: JSONObject) = CMSSite(
            o.optString("key"), o.optString("name"), o.optString("api"), o.optBoolean("adult"), o.optBoolean("enabled", true),
            (0 until (o.optJSONArray("categories")?.length() ?: 0)).map { o.getJSONArray("categories").getJSONObject(it).let { c -> CMSCategory(c.optString("id"), c.optString("name")) } },
            o.optString("origin", "manual"),
        )
    }
}

/**
 * Built-in generic MacCMS adapter (no JS): 1:1 with iOS MacCMSProvider.
 * Series ids in the app are namespaced "cms:<siteKey>:<vod_id>"; episode vids "cms:<siteKey>:<vod_id>#<i>".
 */
object MacCMS {
    const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()
    /** Test hook: url → body. */
    @Volatile var fixture: ((String) -> String?)? = null

    fun sid(site: String, id: String) = "cms:$site:$id"
    fun parseSid(s: String): Pair<String, String>? {
        if (!s.startsWith("cms:")) return null
        val rest = s.removePrefix("cms:"); val i = rest.indexOf(':'); if (i <= 0) return null
        return rest.substring(0, i) to rest.substring(i + 1).substringBefore('#')
    }

    fun s(v: Any?, max: Int = 300): String {
        val t = when (v) { null, JSONObject.NULL -> ""; is String -> v; is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString(); else -> "" }
        return t.trim().take(max)
    }

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("http://")) s = "https://" + s.removePrefix("http://")
        val u = s.toHttpUrlOrNull()
        if (u == null || u.scheme != "https" || u.host.isEmpty() || NetGuard.isIPLiteral(u.host)) throw ProviderException.invalid("需要 https 的采集接口地址（不支持 IP / 内网地址）")
        val b = u.newBuilder().fragment(null)
        for (n in listOf("ac", "t", "pg", "wd", "ids", "h")) b.removeAllQueryParameters(n)
        return b.build().toString()
    }

    fun url(api: String, q: Map<String, String>): String? {
        val u = api.toHttpUrlOrNull() ?: return null
        val b = u.newBuilder(); for ((k, v) in q.toSortedMap()) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    fun fetch(api: String, q: Map<String, String>): JSONObject {
        val u = url(api, q) ?: throw ProviderException.invalid("采集接口地址无效")
        val parsed = u.toHttpUrlOrNull()!!
        if (parsed.scheme != "https") throw ProviderException.invalid("采集接口地址无效")
        val body: String = fixture?.invoke(u) ?: run {
            if (NetGuard.resolvesToBlocked(parsed.host)) throw ProviderException.invalid("采集接口解析到内网地址")
            http.newCall(Request.Builder().url(u).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) throw ProviderException.upstream("采集站 HTTP ${r.code} · ${parsed.host}")
                val fu = r.request.url.host
                if (fu != parsed.host && NetGuard.resolvesToBlocked(fu)) throw ProviderException.invalid("跳转到内网地址")
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (bytes.size >= 8 shl 20) throw ProviderException.upstream("采集站返回过大")
                String(bytes, Charsets.UTF_8)
            }
        }
        return try { JSONObject(body) } catch (_: Exception) { throw ProviderException.upstream("采集站没有返回 JSON（可能只支持 XML 或需要 ?ac=detail）") }
    }

    fun parseCategories(o: JSONObject): List<CMSCategory> {
        val a = o.optJSONArray("class") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            val id = s(c.opt("type_id")); val name = s(c.opt("type_name"), 30)
            if (id.isEmpty() || name.isEmpty() || ContentFilter.isBlocked(listOf(name))) null else CMSCategory(id, name)
        }
    }

    fun fetchCategories(api: String): List<CMSCategory> {
        val o = fetch(api, mapOf("ac" to "list"))
        if (!o.has("list") && !o.has("class")) throw ProviderException.upstream("不像苹果 CMS 接口（缺少 list/class 字段）")
        return parseCategories(o)
    }

    private fun https(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }

    fun parseItems(o: JSONObject, site: String): List<HGSeries> {
        val a = o.optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val v = a.optJSONObject(i) ?: return@mapNotNull null
            val id = s(v.opt("vod_id")); val title = s(v.opt("vod_name"), 200)
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val tags = listOf(s(v.opt("type_name"), 30), s(v.opt("vod_class"), 60)).filter { it.isNotEmpty() }
            if (ContentFilter.isBlocked(listOf(title) + tags)) return@mapNotNull null
            HGSeries(seriesId = sid(site, id), title = title, cover = https(s(v.opt("vod_pic"), 2000)),
                subtitle = s(v.opt("vod_remarks"), 40).ifEmpty { null }, author = s(v.opt("vod_director"), 60).ifEmpty { null },
                orientation = "portrait", tags = tags)
        }
    }

    fun page(o: JSONObject, site: String, p: Int): HGPage {
        val items = parseItems(o, site)
        val pageCount = s(o.opt("pagecount")).toIntOrNull() ?: 0
        return HGPage(items, hasMore = items.isNotEmpty() && (if (pageCount == 0) items.size >= 10 else p < pageCount), nextOffset = p + 1)
    }

    data class Line(val name: String, val eps: List<Pair<String, String>>)

    /** `vod_play_from` "a$$$b", `vod_play_url` "第1集$url#第2集$url$$$..." → lines. */
    fun lines(from: String, urls: String): List<Line> {
        val names = from.split("$$$")
        return urls.split("$$$").mapIndexedNotNull { i, chunk ->
            val eps = chunk.split("#").filter { it.isNotEmpty() }.mapIndexedNotNull { j, e ->
                val k = e.indexOf('$')
                val (n, u) = if (k >= 0) e.substring(0, k) to e.substring(k + 1) else "第${j + 1}集" to e
                if (u.isBlank()) null else n.trim() to u.trim()
            }
            if (eps.isEmpty()) null else Line(if (i < names.size) names[i] else "线路${i + 1}", eps)
        }
    }

    fun isDirect(u: String): Boolean { val l = u.lowercase(); return (l.startsWith("https://") || l.startsWith("http://")) && (l.contains(".m3u8") || l.contains(".mp4")) }

    fun parseDetail(o: JSONObject, s0: HGSeries, siteName: String = "采集站"): HGSeriesDetail {
        val v = o.optJSONArray("list")?.optJSONObject(0) ?: throw ProviderException.upstream("采集站没有返回详情")
        val title = s(v.opt("vod_name"), 200).ifEmpty { s0.title }
        val tags = listOf(s(v.opt("type_name"), 30)) + s(v.opt("vod_class"), 80).split(",")
        if (ContentFilter.isBlocked(listOf(title) + tags)) throw ProviderException.unsupported("该内容已被过滤")
        val all = lines(s(v.opt("vod_play_from"), 2000), v.optString("vod_play_url", ""))
        val direct = all.filter { l -> l.eps.any { isDirect(it.second) } }
        val main = direct.maxByOrNull { it.eps.size } ?: throw ProviderException.unsupported("该资源没有可直接播放的线路（需要解析器的线路暂不支持）")
        val eps = main.eps.mapIndexed { i, e ->
            val alts = JSONArray()
            for (l in direct) if (i < l.eps.size && isDirect(l.eps[i].second)) alts.put(JSONObject().put("n", l.name).put("u", l.eps[i].second))
            HGEpisode(i + 1, "${s0.seriesId}#$i", 0, e.first.ifEmpty { "第${i + 1}集" }, page = main.name, media = alts.toString())
        }
        val cover = https(s(v.opt("vod_pic"), 2000))
        val desc = s(v.opt("vod_content"), 4000).replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
        val meta = listOf(s(v.opt("vod_year")), s(v.opt("vod_area")), s(v.opt("vod_remarks"))).filter { it.isNotEmpty() }.joinToString(" · ")
        return HGSeriesDetail(seriesId = s0.seriesId, title = title, cover = cover.ifEmpty { s0.cover },
            desc = listOf(meta, desc).filter { it.isNotEmpty() }.joinToString("\n"),
            author = s(v.opt("vod_director"), 60),
            actors = s(v.opt("vod_actor"), 300).split(Regex("[,，/]")).map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            totalEps = eps.size, episodes = eps, tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
            episodeLabel = if (eps.size > 1) "共${eps.size}集" else "长视频", source = siteName)
    }

    fun streams(ep: HGEpisode): List<HGStream> {
        val a = try { JSONArray(ep.media ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            var u = o.optString("u"); if (u.isEmpty()) return@mapNotNull null
            if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
            val h = u.toHttpUrlOrNull()?.host ?: return@mapNotNull null
            if (NetGuard.isIPLiteral(h)) return@mapNotNull null
            val hls = u.contains(".m3u8")
            HGStream("cms$i", o.optString("n", "线路${i + 1}") + if (hls) " · HLS" else " · MP4", u,
                kind = if (hls) HGStreamKind.hls else HGStreamKind.mp4Clear, headers = mapOf("User-Agent" to UA))
        }
    }

    // ---- provider ops ----
    fun list(site: CMSSite, categoryId: String, p: Int): HGPage {
        val q = mutableMapOf("ac" to "detail", "pg" to p.toString()); if (categoryId.isNotEmpty()) q["t"] = categoryId
        return page(fetch(site.api, q), site.key, p)
    }
    fun search(site: CMSSite, q: String, p: Int): HGPage = page(fetch(site.api, mapOf("ac" to "detail", "wd" to q, "pg" to p.toString())), site.key, p)
    fun detail(site: CMSSite, seriesId: String): HGSeriesDetail {
        val id = parseSid(seriesId)?.second ?: seriesId
        return parseDetail(fetch(site.api, mapOf("ac" to "detail", "ids" to id)), HGSeries(seriesId = seriesId), site.name)
    }
    fun resolve(site: CMSSite, ep: HGEpisode): HGResolvedVideo {
        var st = streams(ep)
        if (st.isEmpty()) {
            val sid = ep.vid.substringBefore('#')
            val d = detail(site, sid)
            (d.episodes.firstOrNull { it.vid == ep.vid } ?: d.episodes.firstOrNull { it.ep == ep.ep })?.let { st = streams(it) }
        }
        val first = st.firstOrNull() ?: throw ProviderException.unsupported("没有可播放的线路")
        return HGResolvedVideo(ep.vid, url = first.url, streams = st)
    }
}
