package app.iduanju.core.hongguo

import app.iduanju.core.util.J
import app.iduanju.core.util.ProviderException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Detail enrichment ported 1:1 from iOS HongguoClient.swift: official web page (_ROUTER_DATA: tags, cast, favourites),
 * plan/v scene=10 series landing (sibling seasons), related-book walk over detail video_data, SeasonParser.
 */
object HGDetailExtras {
    private const val WEB_ORIGIN = "https://hongguoduanju.com"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    /** Set by HongguoClient init; only its pure parsing helpers are used. */
    @Volatile lateinit var parser: HongguoClient
    private fun looksSID(s: String) = s.length in 15..25 && s.all { it.isDigit() }
    private val web = OkHttpClient.Builder().callTimeout(7, TimeUnit.SECONDS).followRedirects(true).build()

    fun webDetail(sid: String): HGWebDetail {
        if (!looksSID(sid)) throw ProviderException.invalid("seriesId")
        val req = Request.Builder().url("$WEB_ORIGIN/player/$sid")
            .header("User-Agent", WEB_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9").header("Referer", "$WEB_ORIGIN/").build()
        val html = web.newCall(req).execute().use { it.body?.string() ?: "" }
        val router = routerData(html) ?: throw ProviderException.upstream("官网页面缺少 _ROUTER_DATA")
        val ld = J.dict(router.opt("loaderData"))
        val page = J.dict(ld.opt("player_(series_id)/page") ?: ld.opt("player_(series_id)/(vid)/page") ?: ld.opt("detail_page"))
        val sd = J.dict(page.opt("seriesDetail"))
        if (J.str(sd.opt("series_id")) != sid) throw ProviderException.upstream("官网返回的剧集不一致")
        val w = HGWebDetail()
        w.title = J.str(sd.opt("series_name"))
        w.intro = J.str(sd.opt("series_intro")).trim()
        w.tags = J.arr(sd.opt("tags")).map { J.str(it).trim() }.filter { it.isNotEmpty() }
        w.episodeLabel = J.str(sd.opt("episode_right_text")).trim()
        w.seriesGroupId = J.str(J.dict(sd.opt("season_info")).opt("series_group_id"))
        val seen = HashSet<String>(); val cast = ArrayList<HGCelebrity>()
        for (e in J.arr(sd.opt("celebrities"))) {
            val m = J.dict(e)
            if (J.bool(m.opt("is_label"))) continue
            val name = J.firstNonEmpty(J.str(m.opt("nickname")), J.str(m.opt("name")))
            val id = J.firstNonEmpty(J.str(m.opt("celebrity_id")), J.str(m.opt("actor_id")))
            if (name.isEmpty() || !seen.add(id + name)) continue
            cast.add(HGCelebrity(id, name, J.str(m.opt("avatar")), J.firstNonEmpty(J.str(m.opt("sub_title")), J.str(m.opt("role")))))
        }
        w.cast = cast
        val social = J.dict(page.opt("seriesSocialInfo"))
        w.favoriteCount = J.int64(social.opt("series_favorite_count"))
        w.rating = J.double(social.opt("rating"))
        w.ratingCount = J.str(social.opt("rating_count"))
        w.hotText = J.str(J.dict(social.opt("hot_score_data")).opt("text"))
        return w
    }

    /** Brace-matching extractor for `window._ROUTER_DATA = {...}`. */
    fun routerData(html: String): JSONObject? {
        val m = Regex("""(?:window\.)?_ROUTER_DATA\s*=\s*\{""").find(html) ?: return null
        val start = m.range.last
        var depth = 0; var quoted = false; var escaped = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else if (c == '"') quoted = true
            else if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return try { JSONObject(html.substring(start, i + 1)) } catch (_: Exception) { null } }
            i++
        }
        return null
    }

    fun enrich(d: HGSeriesDetail, w: HGWebDetail?): HGSeriesDetail {
        if (w == null) return d
        d.cast = w.cast; d.tags = w.tags; d.favoriteCount = w.favoriteCount; d.episodeLabel = w.episodeLabel
        if (d.desc.isEmpty()) d.desc = w.intro
        if (w.cast.isNotEmpty()) {
            if (d.title.isEmpty()) d.title = w.title
            d.actors = w.cast.map { it.name } // real performer names beat role names from book_info.roles
        }
        d.rating = w.rating; d.ratingCount = w.ratingCount; d.hotText = w.hotText
        return d
    }

    // ---- series landing (plan/v scene=10) ----
    private fun qe(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun seriesLanding(c: HongguoClient, stickIds: String, from: String = "same_ip_same_series_land_page", query: String = "", bookId: String = "", maxPages: Int = 8): List<HGSeries> {
        val ids = stickIds.split(",").map { it.trim() }.filter { looksSID(it) }
        if (ids.isEmpty()) return emptyList()
        val d = c.ensureDevice()
        val out = ArrayList<HGSeries>(); val seen = HashSet<String>()
        var offset = 0; var sessionId = ""
        val fromVal = from.ifEmpty { "same_ip_same_series_land_page" }
        repeat(maxPages) {
            var q = "scene=10&from=" + qe(fromVal) + "&stick_ids=" + qe(ids.joinToString(",")) + "&offset=$offset"
            if (query.isNotEmpty()) q += "&query=" + qe(query)
            if (looksSID(bookId)) q += "&book_id=" + qe(bookId)
            if (sessionId.isNotEmpty()) q += "&session_id=" + qe(sessionId)
            q += "&" + c.listQuery(d, System.currentTimeMillis())
            val j = c.list(HongguoClient.URL_PLAN + q, signed = true)
            if (J.str(j.opt("code")) != "0") {
                if (out.isEmpty()) throw ProviderException.upstream("series landing code=${J.str(j.opt("code"))}")
                return out
            }
            val page = parseSeriesLanding(j)
            if (page.sessionId.isNotEmpty()) sessionId = page.sessionId
            for (s in page.items) if (s.seriesId.isNotEmpty() && seen.add(s.seriesId)) out.add(s)
            if (page.items.isEmpty() || !page.hasMore || page.nextOffset <= offset) return out
            offset = page.nextOffset
        }
        return out
    }

    private class LandingPage(var items: List<HGSeries> = emptyList(), var hasMore: Boolean = false, var nextOffset: Int = 0, var sessionId: String = "")

    private fun parseSeriesLanding(j: JSONObject): LandingPage {
        var data = j
        (j.opt("data") as? JSONObject)?.let { data = it }
        (data.opt("data") as? JSONArray)?.let { a -> (a.opt(0) as? JSONObject)?.let { data = it } }
        val out = LandingPage(hasMore = J.bool(data.opt("has_more")), nextOffset = J.int(data.opt("next_offset")), sessionId = J.str(data.opt("session_id")))
        val items = ArrayList<HGSeries>()
        for (c in J.arr(data.opt("cell_data"))) items.addAll(parseCellVideoData(J.dict(c)))
        out.items = if (items.isEmpty()) parseCellVideoData(data) else items
        return out
    }

    private fun parseCellVideoData(cell: JSONObject): List<HGSeries> {
        var vds = J.arr(cell.opt("video_data"))
        if (vds.isEmpty()) (cell.opt("video_data") as? JSONObject)?.let { vds = listOf(it) }
        val fallback = parser.firstID(cell, "book_id", "search_result_id", "series_id")
        val out = ArrayList<HGSeries>()
        for (v in vds) {
            val s = parser.parseVideoData(J.dict(v))
            if (s.seriesId.isEmpty()) s.seriesId = fallback
            if (s.seriesId.isEmpty() && s.title.isEmpty()) continue
            out.add(s)
        }
        return out
    }

    /** Walk detail video_data for related-book arrays (disable_video_relate_book:false). */
    fun parseRelatedFromDetail(vd: JSONObject): List<HGSeries> {
        if (vd.length() == 0) return emptyList()
        val out = ArrayList<HGSeries>(); val seen = HashSet<String>()
        fun add(s: HGSeries) { if (looksSID(s.seriesId) && seen.add(s.seriesId)) out.add(s) }
        fun take(arr: List<Any?>) {
            for (e in arr) {
                val m = J.dict(e)
                val nested = m.opt("video_data")
                if (nested != null && nested != JSONObject.NULL) {
                    if (nested is JSONArray) {
                        for (v in J.arr(nested)) {
                            val s = parser.parseVideoData(J.dict(v))
                            if (s.seriesId.isEmpty()) s.seriesId = parser.firstID(J.dict(v), "series_id", "book_id", "album_id_str")
                            add(s)
                        }
                    } else if (nested is JSONObject) add(parser.parseVideoData(nested))
                } else {
                    val s = parser.parseVideoData(m)
                    if (s.seriesId.isEmpty()) {
                        s.seriesId = parser.firstID(m, "series_id", "book_id", "album_id_str", "relate_book_id", "related_book_id")
                        if (s.title.isEmpty()) s.title = J.firstNonEmpty(J.str(m.opt("book_name")), J.str(m.opt("series_title")), J.str(m.opt("title")), J.str(m.opt("name")))
                        if (s.cover.isEmpty()) s.cover = J.firstNonEmpty(J.str(m.opt("cover")), J.str(m.opt("thumb_url")), J.str(m.opt("series_cover")))
                        if (s.episodeCount == 0) { s.episodeCount = J.int(m.opt("episode_cnt")); if (s.episodeCount == 0) s.episodeCount = J.int(m.opt("serial_count")) }
                    }
                    add(s)
                }
            }
        }
        val keys = listOf("video_relate_book", "video_relate_book_list", "relate_book_list", "related_book_list",
            "relate_video_list", "related_video_list", "same_series_list", "same_ip_list",
            "relate_books", "related_books", "book_relate_list", "recommend_book_list")
        for (k in keys) {
            when (val v = vd.opt(k)) {
                is JSONArray -> take(J.arr(v))
                is JSONObject -> when {
                    v.opt("book_list") is JSONArray -> take(J.arr(v.opt("book_list")))
                    v.opt("video_data") is JSONArray -> take(J.arr(v.opt("video_data")))
                    v.opt("list") is JSONArray -> take(J.arr(v.opt("list")))
                    else -> take(listOf(v))
                }
            }
        }
        for (k in vd.keys()) {
            val lk = k.lowercase()
            if (lk.contains("relate") || lk.contains("same_ip") || lk.contains("same_series")) (vd.opt(k) as? JSONArray)?.let { take(J.arr(it)) }
        }
        return out
    }

    fun mergeSeries(a: List<HGSeries>, b: List<HGSeries>): List<HGSeries> {
        val seen = HashSet<String>()
        return (a + b).filter { looksSID(it.seriesId) && seen.add(it.seriesId) }
    }

    fun loadRelatedSeries(sid: String, title: String, cover: String, episodeCount: Int, vd: JSONObject, landed: List<HGSeries>?): List<HGSeries> {
        var out = parseRelatedFromDetail(vd)
        if (landed != null) out = mergeSeries(out, landed)
        val seen = HashSet<String>(); val merged = ArrayList<HGSeries>()
        for (s in out) if (s.seriesId.isNotEmpty() && seen.add(s.seriesId)) merged.add(s)
        if (!seen.contains(sid)) { merged.add(0, HGSeries(seriesId = sid, title = title, cover = cover, episodeCount = episodeCount)); seen.add(sid) }
        else merged.firstOrNull { it.seriesId == sid }?.let { m ->
            if (m.title.isEmpty()) m.title = title; if (m.cover.isEmpty()) m.cover = cover; if (m.episodeCount == 0) m.episodeCount = episodeCount
        }
        if (merged.size <= 1) return emptyList()
        if (merged.any { SeasonParser.parse(it.title).explicit }) return SeasonParser.sorted(merged)
        val i = merged.indexOfFirst { it.seriesId == sid }
        if (i > 0) { val cur = merged.removeAt(i); merged.add(0, cur) }
        return merged
    }
}

/** 「第N季/部/期/辑」「Season N」「(N)」 parsing shared by detail + player season pickers (iOS SeasonParser). */
object SeasonParser {
    data class Info(val base: String, val number: Int, val explicit: Boolean)

    private val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val patterns = listOf(
        """[（(【\s]*第\s*([0-9零一二两三四五六七八九十]+)\s*(?:季|部|期|辑)[）)】\s]*$""",
        """[（(【\s]*([0-9零一二两三四五六七八九十]+)\s*(?:季|部|期|辑)[）)】\s]*$""",
        """[（(【\s]*(?:Season|S)\s*([0-9]+)[）)】\s]*$""",
        """[（(【]\s*([0-9]+)\s*[）)】]$""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val c = s.toCharArray()
        if (c.size == 1) return if (c[0] == '十') 10 else digits[c[0]]
        if (c.size == 2 && c[0] == '十') digits[c[1]]?.let { return 10 + it }
        if (c.size == 2 && c[1] == '十') digits[c[0]]?.let { return it * 10 }
        if (c.size == 3 && c[1] == '十') { val a = digits[c[0]]; val b = digits[c[2]]; if (a != null && b != null) return a * 10 + b }
        return null
    }

    private const val TRIM = " :：·_—-"

    fun parse(raw: String): Info {
        val title = raw.trim()
        for (re in patterns) {
            val m = re.find(title) ?: continue
            val n = number(m.groupValues[1]) ?: continue
            if (n <= 0 || n > 100) continue
            val base = title.substring(0, m.range.first).trim { it in TRIM || it.isWhitespace() }
            if (base.isNotEmpty()) return Info(base, n, true)
        }
        return Info(title.trim { it in TRIM || it.isWhitespace() }, 1, false)
    }

    fun label(s: HGSeries): String = "第${parse(s.title).number}季"

    fun sorted(items: List<HGSeries>): List<HGSeries> = items.map { it to parse(it.title) }.sortedWith { a, b ->
        when {
            a.second.number != b.second.number -> a.second.number - b.second.number
            a.second.explicit != b.second.explicit -> if (a.second.explicit) -1 else 1
            else -> b.first.episodeCount - a.first.episodeCount
        }
    }.map { it.first }

    /** iOS VerticalPlayerView.seasonNo: explicit marker, else a 1–2 digit ASCII tail, else 1. */
    fun seasonNo(title: String): Int {
        val i = parse(title)
        if (i.explicit) return i.number
        val tail = title.takeLastWhile { it in '0'..'9' }
        if (tail.length in 1..2) tail.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        return 1
    }
}
