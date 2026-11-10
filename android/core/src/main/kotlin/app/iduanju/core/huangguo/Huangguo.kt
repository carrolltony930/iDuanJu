package app.iduanju.core.huangguo

import app.iduanju.core.hongguo.*
import app.iduanju.core.util.ContentFilter
import app.iduanju.core.util.ProviderException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** iOS HTMLScrape helpers (regex-based, same patterns). */
object HGHtml {
    private fun re(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    fun groups(p: String, s: String): List<String>? = re(p).find(s)?.groupValues
    fun all(p: String, s: String): List<List<String>> = re(p).findAll(s).map { it.groupValues }.toList()
    fun replace(p: String, s: String, with: String) = re(p).replace(s, with)
    fun unescape(s: String) = Parser.unescapeEntities(s, false)
    const val nonContent = "<!--.*?-->|<script\\b[^>]*>.*?</script\\s*>|<style\\b[^>]*>.*?</style\\s*>"
    fun cleanText(s: String): String {
        var t = replace(nonContent, s, " "); t = replace("<[^>]+>", t, " "); t = unescape(t)
        return replace("[\\s\\u00a0\\u3000]+", t, " ").trim()
    }
    fun attr(block: String, vararg names: String): String {
        for (n in names) groups("\\b" + Regex.escape(n) + "\\s*=\\s*[\"']([^\"']+)[\"']", block)?.let { return unescape(it[1]).trim() }
        return ""
    }
    fun classText(raw: String, cls: String) = groups("<[^>]+class=[\"'][^\"']*\\b" + Regex.escape(cls) + "\\b[^\"']*[\"'][^>]*>(.*?)</[a-z0-9]+>", raw)?.let { cleanText(it[1]) } ?: ""
    fun pageTitle(h: String): String {
        groups("<h1[^>]*>(.*?)</h1>", h)?.let { val t = cleanText(it[1]); if (t.isNotEmpty()) return t }
        groups("<meta\\b[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>", h)?.let { return unescape(it[1]).trim() }
        return groups("<title[^>]*>(.*?)</title>", h)?.let { cleanText(it[1]) } ?: ""
    }
    fun description(h: String) = groups("<meta\\b[^>]*(?:name|property)=[\"'](?:description|og:description)[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>", h)?.let { unescape(it[1]) } ?: ""
    fun ogImage(h: String) = groups("<meta\\b[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>", h)?.get(1)?.trim() ?: ""
    fun episodeIndex(s: String, fallback: Int): Int {
        groups("(?:第\\s*0*(\\d+)\\s*(?:集|话|期)|(?:更新至|共|全)\\s*0*(\\d+)\\s*(?:集|话|期)|(?:episode|ep)\\s*#?\\s*0*(\\d+))", s)?.drop(1)?.forEach { g -> g.toIntOrNull()?.takeIf { it > 0 }?.let { return it } }
        return fallback
    }
    fun cleanID(s: String): String { var t = unescape(s).trim(' ', '/', '\t', '\r', '\n', '"', '\''); val i = t.indexOfFirst { it == '?' || it == '#' }; if (i >= 0) t = t.substring(0, i); return t }
    fun resolve(base: String, ref: String): String {
        var r = unescape(ref).trim().replace("\\/", "/"); if (r.isEmpty()) return ""
        if (r.startsWith("//")) r = "https:$r"
        var s = runCatching { URI(base).resolve(URI(r.replace(" ", "%20"))).toString() }.getOrDefault(r)
        if (s.startsWith("http://")) s = "https://" + s.drop(7)
        return s
    }
    fun mediaURL(page: String, raw: String): String {
        val t = unescape(raw).trim(' ', '\t', '\r', '\n', '"', '\''); if (t.isEmpty()) return ""
        val r = resolve(page, t); val l = r.lowercase(); return if (l.contains(".m3u8") || l.contains(".mp4")) r else ""
    }
    fun path(u: String) = runCatching { URI(unescape(u).replace(" ", "%20")).path }.getOrNull() ?: u
    fun detailID(s: String): String { val p = path(s).split('/').filter { it.isNotEmpty() }; for (i in 0 until p.size - 1) if (p[i] == "detail") return cleanID(p[i + 1]); return "" }
    fun titleNeedsRepair(t0: String, id: String): Boolean { val t = t0.trim(); return t.isEmpty() || t == id || t == "短剧" || t == "搜索" || groups("(?:\\b(?:class|type|title|aria-label)\\s*=|(?:data|ata)-search-input|hg-search(?:__|-))", t) != null }
}

enum class HuangguoSub(val label: String) { ai("AI 站"), video("视频站"), legacy("App 源") }
data class HuangguoChannel(val sub: HuangguoSub, val slug: String, val title: String, val isRank: Boolean = false) { val id get() = sub.name + "/" + slug }

/**
 * 黄果 (port of iOS HuangguoClient / gateway provider/huangguo): ai mirrors (HTML + playback API), huangguo.video (data-hls),
 * legacy cloudfront app API (AES-CBC params/responses, guest login). Adult source: the app only shows it behind the adult gate.
 * IDs: series `huangguo:<sub>:<ref with / → ~>`, episode vid `<series id>:<episode key>`.
 */
class HuangguoClient(private val dir: File? = null, private val prefs: MutableMap<String, String> = ConcurrentHashMap()) {
    companion object {
        const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1"
        val aiMirrors = listOf("https://00b4b.adihsppe.cc", "https://bsl8m.adihsppe.cc", "https://hgai12.com", "https://brma.adihsppe.cc", "https://mzofzp.adihsppe.cc",
            "https://rpb7bz.adihsppe.cc", "https://v2tg.adihsppe.cc", "https://huangguoai.com", "https://huangguoai.io", "https://huangguoai.me", "https://huangguoai.ai",
            "https://thu.agdkczeyx.cc", "https://ttvoij.ediayikma.cc")
        const val videoBase = "https://huangguo.video"
        const val frontendURL = "https://d2pypzndaqisk.cloudfront.net"
        val apiHosts = listOf("https://dr6skssi3nxbk.cloudfront.net", "https://d18ka9rqpfd3lo.cloudfront.net", "https://d37n0wjehmw08f.cloudfront.net")
        const val cdnURL = "https://sjljsla.lkkwip.cn"
        const val legacyXUA = "BuildID=com.abc.Butterfly;SysType=ios;DevID=00000000000000000000000000000;Ver=1.0.0;DevType=iPhone;DeviceBrand=APPLE;DeviceModel=iPhone;SystemName=iOS;SystemVersion=18.7;Terminal=1;IsH5=1;Sid=00000000000000000000000000000000"
        val aiChannels = listOf(HuangguoChannel(HuangguoSub.ai, "recommend", "精选推荐"), HuangguoChannel(HuangguoSub.ai, "newest", "最近上新"),
            HuangguoChannel(HuangguoSub.ai, "hot", "热播榜", true), HuangguoChannel(HuangguoSub.ai, "rank-recommend", "推荐榜", true),
            HuangguoChannel(HuangguoSub.ai, "potential", "潜力榜", true), HuangguoChannel(HuangguoSub.ai, "ai-duanju", "AI短剧"),
            HuangguoChannel(HuangguoSub.ai, "ai-manju", "AI漫剧"), HuangguoChannel(HuangguoSub.ai, "ai-mogai", "AI魔改"))
        val videoChannels = listOf("全部", "分类一", "分类二", "分类三", "分类四").mapIndexed { i, t -> HuangguoChannel(HuangguoSub.video, "$i", t) }

        fun isHuangguo(id: String) = id.startsWith("huangguo:")
        fun sid(sub: HuangguoSub, ref: String) = "huangguo:${sub.name}:" + ref.replace("/", "~")
        /** (sub, ref, episodeKey?) */
        fun split(id: String): Triple<HuangguoSub, String, String?>? {
            val p = id.removePrefix("huangguo:").split(":"); if (p.size < 2) return null
            val sub = runCatching { HuangguoSub.valueOf(p[0]) }.getOrNull() ?: return null
            return Triple(sub, p[1].replace("~", "/"), p.getOrNull(2))
        }
        fun deadCoverHost(u: String) = (runCatching { URI(u).host }.getOrNull() ?: "").endsWith("eisees.com")
        fun aiDetailCover(h: String, page: String, listCover: String): String {
            fun clean(s: String) = HGHtml.unescape(s.replace("\\/", "/")).trim()
            for (k in listOf("coverSrc", "posterSrc", "cover_src", "poster", "cover")) HGHtml.groups("\"$k\"\\s*:\\s*\"([^\"]+)\"", h)?.let { m ->
                val u = clean(m[1]); if (u.contains("://") || u.startsWith("/")) { val a = HGHtml.resolve(page, u); if (!deadCoverHost(a)) return a }
            }
            HGHtml.groups("(https://pic\\.drleao\\.cn/[^\"'\\s<>]+)", h)?.let { return clean(it[1]) }
            if (listCover.isNotEmpty()) return listCover
            val og = HGHtml.resolve(page, HGHtml.ogImage(h)); return if (deadCoverHost(og)) "" else og
        }
        fun aiID(url: String): String { val d = HGHtml.detailID(url); if (d.isNotEmpty()) return d; val p = HGHtml.path(url).split('/').filter { it.isNotEmpty() }; return if (p.size == 2 && p[0] == "video") HGHtml.cleanID(p[1]) else "" }
        fun absCover(raw: String, base: String): String {
            val t = raw.trim(); if (t.isEmpty()) return ""
            if (t.startsWith("http://")) return "https://" + t.drop(7); if (t.startsWith("https://")) return t
            if (t.startsWith("//")) return "https:$t"
            return base.trim('/') + "/" + t.trim('/')
        }
        private fun cdnHeaders(key: String, value: String, app: String, enabled: Boolean): Map<String, String>? =
            if (enabled && key.lowercase() == "x-cdn-auth" && value.isNotEmpty() && app.isNotEmpty() && value.length <= 2048 && app.length <= 64 && (value + app).none { it.code <= 0x20 || it.code == 0x7f })
                mapOf("x-cdn-auth" to value, "x-cdn-app" to app) else null
        data class AIPlayback(val main: String, val hevc: String, val cdn: Map<String, String>)
        fun parsePlayback(body: String, id: String, ep: Int): AIPlayback {
            val env = JSONObject(body)
            if (env.optInt("status") != 1 && env.optInt("code") != 200) throw ProviderException.upstream(env.optString("msg").ifEmpty { "黄果 AI 取流失败" })
            val d = env.optJSONObject("data") ?: JSONObject()
            if (d.optString("manifest_method").uppercase() != "GET" || d.optString("id") != id || d.optInt("ep") != ep) throw ProviderException.upstream("黄果 AI 取流参数不匹配")
            val st = d.optJSONObject("streams") ?: JSONObject(); val main = st.optString("main")
            if (!main.startsWith("https://")) throw ProviderException.upstream("黄果 AI 未返回播放地址")
            val c = d.optJSONObject("cdn") ?: JSONObject()
            return AIPlayback(main, st.optString("hevc"), cdnHeaders(c.optString("authKey"), c.optString("authValue"), c.optString("app"), c.optBoolean("m3u8Enabled") || c.optBoolean("otherEnabled")) ?: emptyMap())
        }
        fun aiCDNHeaders(h: String, origin: String): Map<String, String> {
            val m = HGHtml.groups("<script\\b[^>]*id=[\"']videoInitialData[\"'][^>]*>(.*?)</script>", h) ?: return emptyMap()
            val o = runCatching { JSONObject(HGHtml.unescape(m[1])) }.getOrNull() ?: return emptyMap()
            return cdnHeaders(o.optString("cdnAuthKey"), o.optString("cdnAuthValue"), o.optString("cdnApp"), o.optBoolean("cdnM3u8Enabled") || o.optBoolean("cdnOtherEnabled"))?.plus("Origin" to origin) ?: emptyMap()
        }
        fun aesCBC(encrypt: Boolean, input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(input)
        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        fun decryptResponse(s: String, interfaceKey: ByteArray): ByteArray {
            val raw = Base64.getMimeDecoder().decode(s.trim()); if (raw.size < 12) throw ProviderException.invalid("encrypted payload too short")
            val r = interfaceKey + raw.copyOfRange(0, 12); val l = r.size shr 1
            val sh = sha(r).copyOfRange(8, 24)
            val d = sha(sh + r.copyOfRange(0, l)); val f = sha(r.copyOfRange(l, r.size) + sh)
            val key = d.copyOfRange(0, 8) + f.copyOfRange(8, 24) + d.copyOfRange(24, 32)
            val iv = f.copyOfRange(0, 4) + d.copyOfRange(12, 20) + f.copyOfRange(28, 32)
            return aesCBC(false, raw.copyOfRange(12, raw.size), key, iv)
        }
        /** JSONSerialization(.sortedKeys)-style compact encoding of a flat map. */
        fun encodeSorted(m: Map<String, Any>): String = m.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> JSONObject.quote(k) + ":" + (if (v is String) JSONObject.quote(v) else v.toString()) }
        fun queryEscape(s: String): String = URLEncoder.encode(s, "UTF-8")
        /** Non-consensual themes are excluded on top of the minors rule (ContentFilter). */
        val nonConsent = listOf("强奸", "強姦", "强暴", "強暴", "迷奸", "迷姦", "轮奸", "輪姦", "下药", "下藥", "迷药", "迷藥", "催眠", "胁迫", "脅迫", "强迫", "強迫", "被迫",
            "肉偿", "肉償", "凌辱", "侵犯", "猥亵", "猥褻", "非自愿", "囚禁", "绑架", "綁架", "偷拍", "盗摄", "醉奸", "睡奸", "性奴", "调教", "調教", "rape", "forced", "drugged", "hypno")
        fun blocked(vararg texts: String?): Boolean { val t = texts.filterNotNull(); return ContentFilter.isBlocked(t) || t.any { s -> nonConsent.any { s.contains(it, ignoreCase = true) } } }
    }

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val probeHttp = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    @Volatile private var aiBase: String? = prefs["stv.huangguo.aiBase"]?.takeIf { false }
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    @Volatile var fixture: ((String) -> String?)? = null

    private fun fetch(url: String, referer: String, headers: Map<String, String> = emptyMap(), post: String? = null): String {
        fixture?.invoke(url)?.let { return it }
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).header("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> b.header(k, v) }
        if (post != null) b.post(post.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            if (r.code !in 200..299) throw ProviderException.upstream("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
    private fun text(url: String, referer: String, ttl: Long = 300): String {
        cache[url]?.let { (t, s) -> if (System.currentTimeMillis() - t < ttl * 1000) return s }
        return fetch(url, referer).also { cache[url] = System.currentTimeMillis() to it; if (cache.size > 60) cache.clear() }
    }

    // MARK: AI mirrors
    fun mirrorCandidates(): List<String> = (listOfNotNull(prefs["stv.huangguo.aiBase"]) + (prefs["stv.huangguo.aiMirrors"]?.split(",") ?: emptyList()) + aiMirrors).filter { it.isNotEmpty() }.distinct()
    fun discoveredMirrors(html: String): List<String> =
        HGHtml.all("https://((?:[a-z0-9-]+\\.)?(?:adihsppe\\.cc|huangguoai\\.(?:com|io|me|ai)|hgai\\d*\\.com))(?=[/\"'\\s<])", html).map { "https://" + it[1] }.distinct()
    private fun probe(m: String): String? = runCatching {
        if (fixture != null) return fixture!!.invoke("$m/")?.takeIf { it.contains("hg-drama-card") }?.let { m }
        probeHttp.newCall(Request.Builder().url("$m/").header("User-Agent", UA).build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (r.code != 200 || !(body.contains("hg-drama-card") || body.contains("videoInitialData") || body.contains("/ai-duanju/"))) null
            else r.request.url.let { "${it.scheme}://${it.host}" }
        }
    }.getOrNull()
    @Synchronized fun ensureAIBase(): String {
        aiBase?.let { return it }
        val c = mirrorCandidates()
        val pool = Executors.newFixedThreadPool(minOf(8, c.size))
        try {
            val won = runCatching { pool.invokeAny(c.map { m -> java.util.concurrent.Callable { probe(m) ?: throw IllegalStateException() } }, 15, TimeUnit.SECONDS) }.getOrNull()
                ?: throw ProviderException.upstream("黄果 AI 站镜像均不可用（${c.size} 个）")
            aiBase = won; prefs["stv.huangguo.aiBase"] = won
            Thread { runCatching { discoveredMirrors(text("$won/go-home/", "$won/", 3600)).take(24).takeIf { it.isNotEmpty() }?.let { prefs["stv.huangguo.aiMirrors"] = it.joinToString(",") } } }.start()
            return won
        } finally { pool.shutdownNow() }
    }

    // MARK: lists
    fun list(ch: HuangguoChannel, page: Int): HGPage = filtered(when (ch.sub) {
        HuangguoSub.ai -> {
            val base = ensureAIBase()
            if (ch.isRank) {
                if (page > 1) HGPage(emptyList(), false)
                else { val slug = ch.slug.replace("rank-", ""); HGPage(parseRankJSONLD(text("$base/ranks/$slug/", "$base/"), slug, base), false) }
            } else if (ch.slug.startsWith("ai-")) {
                val j = runCatching { aiJSONCards(JSONTokenerAny(fetch("$base/api/videos/category/${ch.slug}?sort=hot&page=$page&size=24", "$base/${ch.slug}/")), base) }.getOrNull()
                if (j != null) HGPage(j, j.size >= 24, page + 1)
                else if (page != 1) HGPage(emptyList(), false)
                else HGPage(parseAICards(text("$base/${ch.slug}/", "$base/"), "$base/${ch.slug}/", base), false)
            } else { val u = "$base/${ch.slug}/$page/"; val it = parseAICards(text(u, "$base/"), u, base); HGPage(it, it.isNotEmpty(), page + 1) }
        }
        HuangguoSub.video -> {
            val q = listOfNotNull(if (ch.slug != "0") "category=${ch.slug}" else null, if (page > 1) "page=$page" else null)
            val u = "$videoBase/videos" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
            val it = parseVideoCards(text(u, "$videoBase/"), u); HGPage(it, it.isNotEmpty(), page + 1)
        }
        HuangguoSub.legacy -> {
            ensureImageCDN()
            val o = legacy("/api/app/playlet/home/tab/" + ch.slug, mapOf("pageNumber" to "$page", "pageSize" to "20", "tabSortType" to "1"))
            val rows = (o as? JSONObject)?.optJSONArray("list")?.objs() ?: emptyList()
            HGPage(rows.mapNotNull { legacyItem(it) }, rows.size >= 20, page + 1)
        }
    })
    private fun filtered(p: HGPage) = p.copy(items = p.items.filter { !blocked(it.title, it.subtitle, *it.tags.toTypedArray()) })

    fun legacyChannels(): List<HuangguoChannel> {
        ensureImageCDN()
        val o = legacy("/api/app/playlet-tab/all", null)
        val rows = (o as? JSONArray)?.objs() ?: (o as? JSONObject)?.optJSONArray("list")?.objs() ?: emptyList()
        return rows.mapNotNull { r -> val id = r.str("id"); val n = r.str("name"); if (id.isEmpty()) null else HuangguoChannel(HuangguoSub.legacy, id, n.ifEmpty { id }) }
    }

    fun search(q: String): List<HGSeries> {
        val needle = q.trim().lowercase(); if (needle.isEmpty()) return emptyList()
        val jobs = aiChannels.filter { !it.isRank }.map { ch -> { list(ch, 1).items } } + listOf({ list(videoChannels[0], 1).items }, {
            legacyChannels().take(6).flatMap { runCatching { list(it, 1).items }.getOrDefault(emptyList()) } })
        val pool = Executors.newFixedThreadPool(jobs.size)
        val res = try { pool.invokeAll(jobs.map { j -> java.util.concurrent.Callable { runCatching(j) } }, 40, TimeUnit.SECONDS).map { runCatching { it.get() }.getOrElse { Result.failure(it) } } } finally { pool.shutdownNow() }
        val all = res.flatMap { it.getOrDefault(emptyList()) }
        val hits = all.filter { it.title.lowercase().contains(needle) }.distinctBy { it.seriesId }
        if (hits.isEmpty() && all.isEmpty()) res.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw ProviderException.upstream(it.message ?: "黄果搜索失败") }
        return hits
    }

    // MARK: detail
    fun detail(seriesId: String, listTitle: String = "", listCover: String = ""): HGSeriesDetail {
        val (sub, ref, _) = split(seriesId) ?: throw ProviderException.invalid("黄果 id 无效")
        fun ep(e: HGEpisode) = e.copy(vid = "$seriesId:" + e.vid.replace(":", "_"))
        val d = when (sub) {
            HuangguoSub.ai -> {
                val base = ensureAIBase(); val page = "$base/video/$ref/"
                val h = text(page, "$base/")
                var eps = parseAIEpisodes(h, page, ref)
                if (eps.isEmpty()) aiVideoURL(h, page).takeIf { it.isNotEmpty() }?.let { eps = listOf(HGEpisode(1, "1", 0, "第1集", page, it)) }
                var title = HGHtml.pageTitle(h); if (HGHtml.titleNeedsRepair(title, ref)) title = listTitle
                HGSeriesDetail(seriesId = seriesId, title = title, cover = aiDetailCover(h, page, listCover), desc = HGHtml.description(h), totalEps = eps.size, episodes = eps.map(::ep))
            }
            HuangguoSub.video -> {
                val path = if (ref.startsWith("series/") || ref.startsWith("video/")) ref else "series/$ref"
                val page = "$videoBase/$path"; val h = text(page, "$videoBase/")
                var eps = if (path.startsWith("video/")) emptyList() else parseVideoEpisodes(h, page)
                if (eps.isEmpty()) dataHLS(h, page)?.let { eps = listOf(HGEpisode(1, "1", 0, "第1集", page, it)) }
                HGSeriesDetail(seriesId = seriesId, title = HGHtml.pageTitle(h).ifEmpty { listTitle }, cover = HGHtml.resolve(page, HGHtml.ogImage(h)).ifEmpty { listCover },
                    desc = HGHtml.description(h), totalEps = eps.size, episodes = eps.map(::ep))
            }
            HuangguoSub.legacy -> {
                ensureImageCDN()
                val raw = legacy("/api/app/playlet/detail/$ref", null) as? JSONObject ?: JSONObject()
                var ch = raw.optJSONArray("chapters")?.objs() ?: emptyList()
                if (ch.isEmpty()) ch = (runCatching { legacy("/api/app/playlet-chapter/list/$ref", null) }.getOrNull() as? JSONArray)?.objs() ?: emptyList()
                val seen = HashSet<String>(); val eps = ArrayList<HGEpisode>()
                ch.forEachIndexed { i, c ->
                    val media = listOf("videoUrl", "video_url", "url", "playUrl").map { c.str(it) }.firstOrNull { it.isNotEmpty() } ?: return@forEachIndexed
                    val key = HGHtml.cleanID(c.str("id")).ifEmpty { HGHtml.cleanID(media) }
                    if (!seen.add(key)) return@forEachIndexed
                    val t = c.str("title").ifEmpty { c.str("name") }
                    var idx = HGHtml.episodeIndex(t, 0); if (idx <= 0) idx = c.optInt("currentEpisode"); if (idx <= 0) idx = i + 1
                    eps.add(HGEpisode(idx, key, c.optInt("mediaTime"), t.ifEmpty { "第${idx}集" }, media = media))
                }
                HGSeriesDetail(seriesId = seriesId, title = listOf(raw.str("title"), raw.str("name"), listTitle).firstOrNull { it.isNotEmpty() } ?: "",
                    cover = absCover(listOf(raw.str("cover"), raw.str("coverUrl"), listCover).firstOrNull { it.isNotEmpty() } ?: "", imageCDN),
                    desc = listOf(raw.str("desc"), raw.str("intro"), raw.str("description")).firstOrNull { it.isNotEmpty() } ?: "",
                    totalEps = maxOf(eps.size, raw.optInt("episodeCount")), episodes = eps.map(::ep))
            }
        }
        if (blocked(d.title, d.desc)) throw ProviderException.invalid("该内容不符合内容规则，已屏蔽")
        d.source = "黄果"; d.episodeLabel = sub.label
        return d
    }

    // MARK: resolve
    fun resolve(ep: HGEpisode): HGResolvedVideo {
        val (sub, ref, _) = split(ep.vid) ?: throw ProviderException.invalid("黄果 id 无效")
        var media = ep.media ?: ""
        var referer: String
        val headers = linkedMapOf("User-Agent" to UA)
        when (sub) {
            HuangguoSub.ai -> {
                val base = ensureAIBase(); referer = "$base/"
                val pb = runCatching { aiPlayback(base, ref, maxOf(1, ep.ep), ep.page) }.getOrNull()
                if (pb != null) { referer = ep.page ?: "$base/video/$ref/"; headers["Origin"] = base; headers.putAll(pb.cdn); media = pb.main }
                else if (ep.page != null) { val h = text(ep.page, referer, 30); if (media.isEmpty()) media = aiVideoURL(h, ep.page); headers.putAll(aiCDNHeaders(h, base)) }
            }
            HuangguoSub.video -> { referer = "$videoBase/"; if (media.isEmpty() && ep.page != null) media = dataHLS(text(ep.page, referer, 30), ep.page) ?: "" }
            HuangguoSub.legacy -> {
                val a = legacyAccess(); val base = apiBase(); referer = "$frontendURL/"
                media = "$base/api/app/vid/h5/m3u8/${media.trimStart('/')}?token=${queryEscape(a.token)}&c=${queryEscape(cdnURL)}"
                headers["Origin"] = frontendURL; headers["X-User-Agent"] = legacyUA(a)
            }
        }
        headers["Referer"] = referer
        if (media.isEmpty()) throw ProviderException.upstream("未解析到播放地址（可能需要登录或链接失效）")
        val hls = media.lowercase().contains(".m3u8") || media.contains("/m3u8/")
        // ExoPlayer handles variants and AES-128 keys itself (same headers on key requests)
        val st = HGStream(if (hls) "hls" else "mp4", "原画", media, kind = if (hls) HGStreamKind.hls else HGStreamKind.mp4Clear, headers = headers)
        return HGResolvedVideo(ep.vid, media, streams = listOf(st))
    }

    fun aiPlayback(base: String, id: String, ep: Int, page: String?): AIPlayback {
        if (!id.all { it.isDigit() }) throw ProviderException.invalid("bad huangguo id")
        val body = fetch("$base/api/videos/$id/episodes/$ep/playback", page ?: "$base/video/$id/", mapOf("Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest", "Origin" to base), post = "{}")
        return parsePlayback(body, id, ep)
    }

    // MARK: AI parsing
    private fun aiItem(id: String, title: String, cover: String = "", eps: Int = 0, rank: Int = 0, sub: String? = null) =
        HGSeries(seriesId = sid(HuangguoSub.ai, id), title = title, cover = cover, rank = rank, subtitle = sub, episodeCount = eps)

    fun parseAICards(html: String, pageURL: String, base: String): List<HGSeries> {
        val markup = HGHtml.replace(HGHtml.nonContent, html, "")
        val out = ArrayList<HGSeries>(); val pos = HashMap<String, Int>()
        fun add(s: HGSeries) { pos[s.seriesId]?.let { p -> if (out[p].cover.isEmpty()) out[p].cover = s.cover; return }; pos[s.seriesId] = out.size; out.add(s) }
        val starts = Regex("<div\\b[^>]*class=[\"'][^\"']*\\bhg-drama-card\\b[^\"']*[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(markup).map { it.range.first }.toList()
        starts.forEachIndexed { i, st ->
            val block = markup.substring(st, if (i + 1 < starts.size) starts[i + 1] else markup.length)
            var id = HGHtml.cleanID(HGHtml.attr(block, "data-track-id"))
            if (id.isEmpty()) HGHtml.groups("href=[\"']([^\"']*/detail/([^\"'/?#]+)[^\"']*)[\"']", block)?.let { id = HGHtml.cleanID(it[2]) }
            if (id.isEmpty()) return@forEachIndexed
            val title = listOf(HGHtml.attr(block, "data-track-title"), HGHtml.classText(block, "hg-drama-card__title"), HGHtml.attr(block, "title"), HGHtml.attr(block, "alt")).firstOrNull { !HGHtml.titleNeedsRepair(it, id) } ?: id
            val epText = HGHtml.classText(block, "hg-drama-card__episode")
            add(aiItem(id, title, HGHtml.resolve(pageURL, HGHtml.attr(block, "data-src", "data-original", "src")), HGHtml.attr(block, "data-ep-base").toIntOrNull() ?: HGHtml.episodeIndex(epText, 0),
                sub = listOf(HGHtml.classText(block, "hg-drama-card__badge"), epText).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }))
        }
        for (m in HGHtml.all("<a\\b[^>]*href=[\"']([^\"']*/detail/([^\"'/?#]+)[^\"']*)[\"'][^>]*>.*?</a>", markup)) {
            val id = HGHtml.cleanID(m[2]); if (id.isEmpty()) continue
            val t = listOf(HGHtml.attr(m[0], "title", "alt", "aria-label"), HGHtml.cleanText(m[0])).firstOrNull { !HGHtml.titleNeedsRepair(it, id) } ?: continue
            add(aiItem(id, t, HGHtml.resolve(pageURL, HGHtml.attr(m[0], "data-src", "data-original", "src"))))
        }
        return out
    }

    fun aiJSONCards(obj: Any?, base: String): List<HGSeries> {
        val out = ArrayList<HGSeries>(); val seen = HashSet<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    if (v.has("id")) {
                        var id = listOf(v.str("id"), v.str("videoId"), v.str("slug")).firstOrNull { it.isNotEmpty() } ?: ""
                        HGHtml.detailID(id).takeIf { it.isNotEmpty() }?.let { id = it }; id = HGHtml.cleanID(id)
                        val title = listOf(v.str("title"), v.str("name"), v.str("videoTitle")).firstOrNull { it.isNotEmpty() } ?: ""
                        if (id.isNotEmpty() && !HGHtml.titleNeedsRepair(title, id) && seen.add(id)) {
                            val cover = listOf("cover", "coverUrl", "cover_url", "coverImage", "image", "thumbnail", "poster").map { v.str(it) }.firstOrNull { it.isNotEmpty() } ?: ""
                            val eps = listOf("episode_count", "episodeCount", "total_episodes", "totalEpisodes", "episodes").map { v.optDouble(it, 0.0) }.firstOrNull { it > 0 }?.toInt() ?: 0
                            out.add(aiItem(id, title, HGHtml.resolve("$base/", cover), eps, sub = listOf(v.str("remark"), v.str("vod_remarks")).firstOrNull { it.isNotEmpty() }))
                        }
                        return
                    }
                    v.keys().forEach { walk(v.opt(it)) }
                }
            }
        }
        walk(obj); return out
    }

    fun parseRankJSONLD(html: String, slug: String, base: String): List<HGSeries> {
        val covers = HashMap<String, String>()
        for (m in HGHtml.all("href=[\"']([^\"']*/video/(\\d+)[^\"']*)[\"']", html)) {
            val id = m[2]; if (covers.containsKey(id)) continue
            val at = html.indexOf(m[0]); if (at >= 0) HGHtml.groups("https://pic\\.drleao\\.cn[^\"\\s<>]+", html.substring(at, minOf(html.length, at + 1200)))?.let { covers[id] = it[0] }
        }
        for (b in HGHtml.all("<script[^>]*ld\\+json[^>]*>(.*?)</script>", html)) {
            val o = runCatching { JSONObject(HGHtml.unescape(b[1])) }.getOrNull() ?: continue
            for (g in o.optJSONArray("@graph")?.objs() ?: emptyList()) if (g.str("@type") == "ItemList" && g.str("@id").endsWith("/ranks/$slug/#itemlist"))
                return (g.optJSONArray("itemListElement")?.objs() ?: emptyList()).mapNotNull { e -> val id = aiID(e.str("url")); val n = e.str("name"); if (id.isEmpty() || n.isEmpty()) null else aiItem(id, n, covers[id] ?: "", rank = e.optInt("position")) }
        }
        return emptyList()
    }

    fun parseAIEpisodes(html: String, pageURL: String, sourceID: String): List<HGEpisode> {
        val markup = HGHtml.replace(HGHtml.nonContent, html, "")
        val ms = HGHtml.all("<a\\b[^>]*href=[\"']([^\"']*/video/[^\"']+)[\"'][^>]*>.*?</a>", markup)
        fun link(href: String): Pair<String, String>? {
            val full = HGHtml.resolve(pageURL, href); val p = HGHtml.path(full).split('/').filter { it.isNotEmpty() }
            if (!(p.size == 2 || p.size == 3) || p[0] != "video" || p[1] != sourceID) return null
            return full to (if (p.size == 3) HGHtml.cleanID(p[2]) else "1")
        }
        fun control(a: String) = HGHtml.attr(a, "data-ep-id").isNotEmpty() || HGHtml.attr(a, "class").split(' ').contains("hg-web-play__ep")
        val marked = ms.any { link(it[1]) != null && control(it[0]) }
        val seen = HashSet<String>(); val used = HashSet<Int>(); val eps = ArrayList<HGEpisode>()
        for (m in ms) {
            val (full, key) = link(m[1]) ?: continue
            if ((marked && !control(m[0])) || key in seen || full in seen) continue
            seen.add(key); seen.add(full)
            val title = HGHtml.attr(m[0], "title", "aria-label").ifEmpty { HGHtml.cleanText(m[0]) }
            var idx = HGHtml.attr(m[0], "data-ep-id").toIntOrNull() ?: 0
            if (idx <= 0) { val e = HGHtml.groups("/ep-(\\d+)(?:/|$)", full); if (e != null) idx = e[1].toIntOrNull() ?: 0 else if (key == "1") idx = 1 }
            if (idx <= 0) idx = HGHtml.episodeIndex(title, eps.size + 1)
            while (idx in used) idx++
            used.add(idx)
            val label = if (title.isEmpty() || title.toIntOrNull() != null || title in listOf("立即播放", "播放", "选集", "正片", "在线观看")) "第${idx}集" else title
            eps.add(HGEpisode(idx, key, 0, label, full))
        }
        return eps.sortedBy { it.ep }
    }

    private fun aiVideoURL(h: String, pageURL: String): String {
        HGHtml.groups("<script\\b[^>]*id=[\"']videoInitialData[\"'][^>]*>(.*?)</script>", h)?.let { m -> runCatching { JSONObject(HGHtml.unescape(m[1])) }.getOrNull()?.let { o ->
            HGHtml.mediaURL(pageURL, listOf("videoSrc", "videoUrl", "playUrl").map { o.str(it) }.firstOrNull { it.isNotEmpty() } ?: "").takeIf { it.isNotEmpty() }?.let { return it }
            val eps = o.optJSONObject("epPlaySrcs") ?: JSONObject()
            var pref = o.str("ep").ifEmpty { o.str("episode") }
            HGHtml.groups("/ep-(\\d+)(?:/|$)", pageURL)?.let { pref = it[1] }
            if (pref.isNotEmpty()) HGHtml.mediaURL(pageURL, eps.str(pref)).takeIf { it.isNotEmpty() }?.let { return it }
            for (k in eps.keys().asSequence().sortedBy { HGHtml.episodeIndex(it, it.toIntOrNull() ?: 0) }) HGHtml.mediaURL(pageURL, eps.str(k)).takeIf { it.isNotEmpty() }?.let { return it }
        } }
        HGHtml.groups("data-play-src=[\"']([^\"']+)[\"']", h)?.let { HGHtml.mediaURL(pageURL, it[1]).takeIf { u -> u.isNotEmpty() }?.let { u -> return u } }
        for (m in HGHtml.all("[\"']?(?:videoSrc|videoUrl|playUrl)[\"']?\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4)(?:[^\"']*)?)[\"']", h)) HGHtml.mediaURL(pageURL, m[1]).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    // MARK: video site
    private val mediaLink = "<a\\b[^>]*href=[\"']([^\"']*/(?:series|video)/([^\"'/?#]+)[^\"']*)[\"'][^>]*>.*?</a>"
    private fun videoRef(href: String): Pair<String, String>? {
        val p = HGHtml.path(href).split('/').filter { it.isNotEmpty() }
        for (i in 0 until p.size - 1) if (p[i] == "series" || p[i] == "video") { val c = HGHtml.cleanID(p[i + 1]); return if (c.isEmpty()) null else p[i] to "${p[i]}/$c" }
        return null
    }
    private fun videoCards(h: String): List<String> {
        val b = HGHtml.all("<article\\b[^>]*class=[\"'][^\"']*\\bvideo-card\\b[^\"']*[\"'][^>]*>.*?</article>", h).map { it[0] }
        return b.ifEmpty { HGHtml.all(mediaLink, h).map { it[0] } }
    }
    fun parseVideoCards(h: String, pageURL: String): List<HGSeries> {
        val seen = HashSet<String>()
        return videoCards(h).mapNotNull { b ->
            val l = HGHtml.groups(mediaLink, b) ?: return@mapNotNull null
            val (_, ref) = videoRef(l[1]) ?: return@mapNotNull null
            if (!seen.add(ref)) return@mapNotNull null
            HGSeries(seriesId = sid(HuangguoSub.video, ref), title = HGHtml.attr(b, "title", "alt", "aria-label").ifEmpty { HGHtml.cleanText(b) },
                cover = HGHtml.resolve(pageURL, HGHtml.attr(b, "data-src", "data-original", "src")), subtitle = HGHtml.classText(b, "text-gold-dim").ifEmpty { null })
        }
    }
    fun parseVideoEpisodes(h: String, pageURL: String): List<HGEpisode> {
        val seen = HashSet<String>(); val eps = ArrayList<HGEpisode>()
        for (b in videoCards(h)) {
            val l = HGHtml.groups(mediaLink, b) ?: continue
            val (kind, ref) = videoRef(l[1]) ?: continue
            if (kind != "video") continue
            val key = ref.removePrefix("video/"); if (!seen.add(key)) continue
            val t = HGHtml.attr(b, "title").ifEmpty { HGHtml.attr(b, "alt") }.ifEmpty { HGHtml.cleanText(b) }
            eps.add(HGEpisode(HGHtml.episodeIndex(t, eps.size + 1), key, 0, t, HGHtml.resolve(pageURL, l[1]), dataHLS(b, pageURL)))
        }
        return eps.sortedBy { it.ep }
    }
    private fun dataHLS(h: String, page: String) = HGHtml.groups("data-hls=[\"']([^\"']+)[\"']", h)?.let { HGHtml.mediaURL(page, it[1]) }?.takeIf { it.isNotEmpty() }

    // MARK: legacy cloudfront API
    data class LegacyAccess(val interfaceKey: String, val paramKey: String, val paramIV: String, var token: String, val deviceId: String) {
        fun json() = JSONObject().put("interfaceKey", interfaceKey).put("paramKey", paramKey).put("paramIV", paramIV).put("token", token).put("deviceId", deviceId).toString()
    }
    @Volatile private var access: LegacyAccess? = null
    @Volatile private var api: String? = null
    @Volatile private var imageCDN = "https://zzzznnn.lkkwip.cn"
    private val sessionFile get() = dir?.let { File(it, "huangguo_session.json") }
    fun resetSession() { access = null; api = null; aiBase = null; cache.clear(); prefs.remove("stv.huangguo.aiBase"); sessionFile?.delete() }
    private fun legacyUA(a: LegacyAccess) = legacyXUA.replace("DevID=00000000000000000000000000000", "DevID=" + a.deviceId)

    private fun frontendScript(): String {
        val page = text("$frontendURL/", "$frontendURL/")
        for (m in HGHtml.all("<script\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", page)) if (m[1].contains("/main-") && m[1].endsWith(".js")) {
            val u = HGHtml.resolve("$frontendURL/", m[1]); if (URI(u).host == URI(frontendURL).host) return text(u, "$frontendURL/", 3600)
        }
        throw ProviderException.upstream("黄果官网未提供可识别的接口脚本")
    }
    @Synchronized private fun apiBase(): String {
        api?.let { return it }
        var cands = apiHosts
        for (pass in 0..1) {
            if (pass == 1) cands = HGHtml.all("https://[a-z0-9]+\\.cloudfront\\.net", runCatching { frontendScript() }.getOrNull() ?: break).map { it[0] }.filter { it != frontendURL }.distinct()
            for (c in cands) if (runCatching { JSONObject(fetch("$c/api/app/ping/check", "$frontendURL/")).optInt("code") == 200 }.getOrDefault(false)) { api = c; return c }
        }
        throw ProviderException.upstream("黄果旧 API 候选域名均不可用")
    }
    @Synchronized private fun legacyAccess(): LegacyAccess {
        access?.let { return it }
        sessionFile?.takeIf { it.exists() }?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull()?.let { o ->
            val a = LegacyAccess(o.str("interfaceKey"), o.str("paramKey"), o.str("paramIV"), o.str("token"), o.str("deviceId")); if (a.token.isNotEmpty()) { access = a; return a } } }
        val s = frontendScript()
        fun v(n: String) = HGHtml.groups("[\"']$n[\"']\\s*[:,]\\s*[\"']([^\"'\\\\\\r\\n]+)[\"']", s)?.get(1) ?: ""
        val ik = v("interfaceKey"); val pk = v("parameterKey"); val piv = v("parameterIv")
        if (ik.isEmpty() || pk.toByteArray().size != 16 || piv.toByteArray().size != 16) throw ProviderException.upstream("黄果官网协议已变化，无法识别当前接口参数")
        val dev = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) } + System.currentTimeMillis()
        val a = LegacyAccess(ik, pk, piv, "", dev)
        val tok = (legacyRequest("POST", "/api/app/mine/login/h5", mapOf("devID" to dev, "sysType" to "ios", "isAppStore" to false), a) as? JSONObject)?.str("token") ?: ""
        if (tok.isEmpty() || tok.contains(" ")) throw ProviderException.upstream("黄果访客登录未返回有效令牌")
        a.token = tok; access = a
        runCatching { sessionFile?.writeText(a.json()) }
        return a
    }
    private fun legacy(path: String, params: Map<String, Any>?): Any {
        repeat(2) { attempt ->
            val a = legacyAccess()
            try { return legacyRequest("GET", path, params, a) } catch (e: ProviderException) {
                if (attempt == 0 && (e.message ?: "").startsWith("5005")) { access = null; sessionFile?.delete() } else throw e
            }
        }
        throw ProviderException.upstream("黄果访客登录已失效，请稍后重试")
    }
    private fun legacyRequest(method: String, path: String, params: Map<String, Any>?, a: LegacyAccess): Any {
        val base = apiBase()
        var full = path; var body: String? = null
        if (params != null) {
            val enc = Base64.getEncoder().encodeToString(aesCBC(true, encodeSorted(params).toByteArray(), a.paramKey.toByteArray(), a.paramIV.toByteArray()))
            if (method == "GET") full += (if (full.contains("?")) "&" else "?") + "data=" + queryEscape(enc) else body = encodeSorted(mapOf("data" to enc))
        }
        val b = Request.Builder().url(base + full).header("Accept", "application/json").header("temp", "test").header("X-User-Agent", legacyUA(a))
            .header("Origin", frontendURL).header("Referer", "$frontendURL/").header("User-Agent", UA)
        if (a.token.isNotEmpty()) b.header("Authorization", a.token)
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        val txt = fixture?.invoke(base + full) ?: http.newCall(b.build()).execute().use { r ->
            if (r.code !in 200..299) { if (r.code >= 500) api = null; throw ProviderException.upstream("黄果旧 API 返回 HTTP ${r.code}") }
            r.body?.string() ?: ""
        }
        val env = JSONObject(txt)
        val c = env.opt("code")?.toString() ?: ""
        if (c.isNotEmpty() && c != "200" && c != "0") throw ProviderException.upstream("$c " + env.optString("msg").replace(a.token, "[已隐藏]"))
        val hash = env.opt("hash")
        val hashOn = when (hash) { is Boolean -> hash; is String -> hash.lowercase() !in listOf("", "false", "0", "null"); null, JSONObject.NULL -> false; else -> true }
        val data = env.opt("data")
        if (hashOn && data is String) return JSONTokenerAny(String(decryptResponse(data, a.interfaceKey.toByteArray())))
            ?: throw ProviderException.upstream("黄果旧 API 解密失败")
        if (data == null || data == JSONObject.NULL) throw ProviderException.upstream("黄果旧 API 未返回数据")
        return data
    }
    private fun legacyItem(r: JSONObject): HGSeries? {
        val id = HGHtml.cleanID(listOf("id", "dramaId", "playletId").map { r.str(it) }.firstOrNull { it.isNotEmpty() } ?: ""); if (id.isEmpty()) return null
        return HGSeries(seriesId = sid(HuangguoSub.legacy, id), title = listOf("title", "name", "dramaName").map { r.str(it) }.firstOrNull { it.isNotEmpty() } ?: id,
            cover = absCover(listOf("cover", "coverUrl", "image", "imageUrl", "img", "poster").map { r.str(it) }.firstOrNull { it.isNotEmpty() } ?: "", imageCDN),
            subtitle = listOf("remark", "statusText").map { r.str(it) }.firstOrNull { it.isNotEmpty() },
            episodeCount = listOf("episodeCount", "episode_count", "totalEpisodes", "total").map { r.optDouble(it, 0.0) }.firstOrNull { it > 0 }?.toInt() ?: 0,
            playCount = r.optLong("playCount"))
    }
    private fun ensureImageCDN() {
        val d = runCatching { legacy("/api/app/ping/domain/h5", null) }.getOrNull() as? JSONObject ?: return
        for (r in d.optJSONArray("sourceList")?.objs() ?: emptyList()) if (r.str("type").uppercase() == "IMAGE") {
            val ds = r.optJSONArray("domain")?.objs() ?: emptyList()
            val u = (ds.firstOrNull { it.optInt("status") == 0 } ?: ds.firstOrNull())?.str("url") ?: ""
            if (u.isNotEmpty()) { imageCDN = u.trim('/'); return }
        }
    }
}

private fun JSONArray.objs() = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.str(k: String): String = when (val v = opt(k)) { null, JSONObject.NULL -> ""; is String -> v; is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString(); else -> v.toString() }
/** Parses any JSON value (object / array / fragment). */
internal fun JSONTokenerAny(s: String): Any? = runCatching { org.json.JSONTokener(s.trim()).nextValue() }.getOrNull()
