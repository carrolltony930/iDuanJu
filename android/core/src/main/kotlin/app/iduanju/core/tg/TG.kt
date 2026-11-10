package app.iduanju.core.tg

import app.iduanju.core.hongguo.*
import app.iduanju.core.util.ProviderException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Telegram public channel read through the web preview `t.me/s/<name>` (iOS TGModels/TGParser/TGClient). No account, no keys. */
data class TGChannel(val username: String, var title: String = "", var avatar: String = "", var subscribers: String = "", var about: String = "",
                     val isAdult: Boolean = false, val addedAt: Long = System.currentTimeMillis()) {
    val id get() = username.lowercase()
    val displayName get() = title.ifEmpty { "@$username" }
    fun toJSON(): JSONObject = JSONObject().put("username", username).put("title", title).put("avatar", avatar).put("subscribers", subscribers).put("about", about).put("isAdult", isAdult).put("addedAt", addedAt)
    companion object {
        fun fromJSON(o: JSONObject) = TGChannel(o.optString("username"), o.optString("title"), o.optString("avatar"), o.optString("subscribers"), o.optString("about"), o.optBoolean("isAdult"), o.optLong("addedAt"))
        /** Accepts `name`, `@name`, `t.me/name`, `https://t.me/s/name`, `https://t.me/name/123`. */
        fun normalize(raw: String): String? {
            var s = raw.trim()
            for (p in listOf("https://", "http://")) if (s.lowercase().startsWith(p)) s = s.drop(p.length)
            for (p in listOf("www.t.me/", "t.me/s/", "t.me/", "telegram.me/s/", "telegram.me/", "@")) if (s.lowercase().startsWith(p)) s = s.drop(p.length)
            if (s.lowercase().startsWith("s/")) s = s.drop(2)
            s = s.split('/', '?', '#').firstOrNull() ?: ""
            return if (s.length in 4..64 && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) s else null
        }
    }
}

data class TGMedia(val kind: Kind, val thumb: String, val video: String? = null, val duration: Int = 0, val link: String = "", val spoiler: Boolean = false) {
    enum class Kind { photo, video, gif }
}
data class TGButton(val text: String, val url: String)
data class TGLinkPreview(val site: String, val title: String, val desc: String, val image: String, val url: String)
data class TGPost(val channel: String, val msgId: Int, var date: Long = 0, var author: String = "", var text: String = "", var media: List<TGMedia> = emptyList(),
                  var links: List<String> = emptyList(), var buttons: List<TGButton> = emptyList(), var preview: TGLinkPreview? = null,
                  var views: String = "", var forwardedFrom: String = "", var isService: Boolean = false) {
    val id get() = channel.lowercase() + "/" + msgId
    val permalink get() = "https://t.me/$channel/$msgId"
}
data class TGChannelPage(val info: TGChannel?, val posts: List<TGPost>, val before: Int?)

object TGParser {
    private fun bg(e: Element?): String = e?.attr("style")?.let { Regex("background-image:url\\('([^']+)'\\)").find(it)?.groupValues?.get(1) } ?: ""
    private fun clock(s: String) = s.split(":").fold(0) { a, p -> a * 60 + (p.toIntOrNull() ?: 0) }
    /** Message HTML → plain text keeping line breaks. */
    fun text(e: Element?): String {
        if (e == null) return ""
        val c = e.clone(); c.select("br").forEach { it.after("\n"); it.remove() }
        return org.jsoup.parser.Parser.unescapeEntities(c.wholeText(), false).trim()
    }

    fun page(html: String, channel: String): TGChannelPage {
        val doc = Jsoup.parse(html, "https://t.me/")
        val posts = doc.select("div.tgme_widget_message_wrap").mapNotNull { post(it) }.sortedByDescending { it.msgId }
        val before = Regex("data-before=\"(\\d+)\"").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("<link rel=\"prev\" href=\"[^\"]*before=(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return TGChannelPage(info(doc, channel), posts, before)
    }

    fun info(doc: org.jsoup.nodes.Document, channel: String): TGChannel? {
        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val user = doc.selectFirst(".tgme_channel_info_header_username a")?.text()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: channel
        val subs = doc.select(".tgme_channel_info_counter").firstOrNull { it.selectFirst(".counter_type")?.text()?.startsWith("subscriber") == true }?.selectFirst(".counter_value")?.text() ?: ""
        return TGChannel(user, title, doc.selectFirst("i.tgme_page_photo_image img")?.attr("src") ?: "", subs, text(doc.selectFirst(".tgme_channel_info_description")))
    }

    fun post(w: Element): TGPost? {
        val m = w.selectFirst("[data-post]") ?: return null
        val dp = m.attr("data-post").split("/"); if (dp.size != 2) return null
        val id = dp[1].toIntOrNull() ?: return null
        val p = TGPost(dp[0], id)
        p.isService = m.hasClass("service_message") || w.outerHtml().contains("service_message")
        p.author = w.selectFirst(".tgme_widget_message_from_author")?.text().orEmpty()
        p.forwardedFrom = w.selectFirst(".tgme_widget_message_forwarded_from_name")?.text().orEmpty()
        w.selectFirst("time[datetime]")?.attr("datetime")?.let { p.date = runCatching { java.time.OffsetDateTime.parse(it).toEpochSecond() }.getOrDefault(0) }
        p.views = w.selectFirst(".tgme_widget_message_views")?.text().orEmpty()
        // the post's own text is the last js-message_text (a reply quote comes first)
        w.select("div.js-message_text").lastOrNull()?.let { body ->
            p.text = text(body)
            p.links = body.select("a[href]").map { it.attr("href") }.filter { it.startsWith("http") || it.startsWith("tg:") }
        }
        p.media = w.select("a.tgme_widget_message_photo_wrap, a.tgme_widget_message_video_player").mapNotNull { a ->
            val link = a.attr("href").replace("?single", "")
            val spoiler = a.className().contains("blured")
            if (a.hasClass("tgme_widget_message_photo_wrap")) bg(a).takeIf { it.isNotEmpty() }?.let { TGMedia(TGMedia.Kind.photo, it, link = link, spoiler = spoiler) }
            else {
                val src = a.selectFirst("video[src]")?.attr("src")
                val dur = a.selectFirst(".message_video_duration")?.text()?.let(::clock) ?: 0
                val gif = a.selectFirst(".tgme_widget_message_gif") != null || (src != null && dur == 0 && a.selectFirst("video[loop]") != null)
                TGMedia(if (gif) TGMedia.Kind.gif else TGMedia.Kind.video, bg(a.selectFirst("i.tgme_widget_message_video_thumb")), src, dur, link, spoiler)
            }
        }
        p.buttons = w.select("a.tgme_widget_message_inline_button[href]").map { TGButton(it.text().trim(), it.attr("href")) }
        w.selectFirst("a.tgme_widget_message_link_preview[href]")?.let { lp ->
            p.preview = TGLinkPreview(lp.selectFirst(".link_preview_site_name")?.text().orEmpty(), lp.selectFirst(".link_preview_title")?.text().orEmpty(),
                lp.selectFirst(".link_preview_description")?.text().orEmpty(), bg(lp.selectFirst("i[style]")), lp.attr("href"))
        }
        if (p.text.isEmpty() && p.media.isEmpty() && p.preview == null && !p.isService) p.text = w.selectFirst(".message_media_not_supported_label")?.text().orEmpty()
        return p
    }
}

class TGClient(var base: String = "https://t.me") {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile var fixture: ((String) -> Pair<String, String>?)? = null

    fun page(channel: String, before: Int? = null, query: String? = null): TGChannelPage {
        val q = listOfNotNull(query?.takeIf { it.isNotEmpty() }?.let { "q=" + java.net.URLEncoder.encode(it, "UTF-8") }, before?.let { "before=$it" })
        val (html, final) = get("$base/s/$channel" + if (q.isEmpty()) "" else "?" + q.joinToString("&"))
        if (!java.net.URI(final).path.startsWith("/s/")) throw ProviderException("@$channel 的网页预览不可用（频道关闭了网页预览或不是公开频道）。可「在 Telegram 中打开」。")
        return TGParser.page(html, channel)
    }

    /** Fresh copy of one post (video URLs carry an expiring token). */
    fun post(channel: String, id: Int): TGPost? = page(channel, before = id + 1).posts.firstOrNull { it.msgId == id }

    private fun get(url: String): Pair<String, String> {
        fixture?.invoke(url)?.let { return it }
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build()).execute().use { r ->
            if (r.code !in 200..299) throw ProviderException("Telegram 网页预览 HTTP ${r.code}")
            return (r.body?.string() ?: "") to r.request.url.toString()
        }
    }

    companion object { const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1" }
}

/** Telegram videos reuse the long-video player: series id `tg:<channel>:<msgId>:<mediaIndex>` (no slashes: it travels in nav routes) (iOS TGVideoProvider). */
object TGVideo {
    fun isTG(id: String) = id.startsWith("tg:")
    fun sid(p: TGPost, i: Int) = "tg:${p.channel}:${p.msgId}:$i"
    fun series(p: TGPost, i: Int, ch: TGChannel?): HGSeries? {
        val m = p.media.getOrNull(i) ?: return null; if (m.video == null) return null
        val title = p.text.lineSequence().firstOrNull()?.take(60).orEmpty()
        return HGSeries(seriesId = sid(p, i), title = title.ifEmpty { "${ch?.displayName ?: "@" + p.channel} · 视频" }, cover = m.thumb,
            subtitle = ch?.displayName ?: "@${p.channel}", author = p.channel, duration = m.duration, onlineTime = p.date, orientation = "landscape")
    }
    private fun parts(id: String): Triple<String, Int, Int>? { val s = id.removePrefix("tg:").split(":"); return if (s.size == 3) Triple(s[0], s[1].toIntOrNull() ?: return null, s[2].toIntOrNull() ?: return null) else null }
    fun detail(client: TGClient, id: String, ch: TGChannel?): HGSeriesDetail {
        val (c, msg, i) = parts(id) ?: throw ProviderException.invalid("频道视频 id 无效")
        val p = client.post(c, msg) ?: throw ProviderException("帖子已删除或不可见")
        val s = series(p, i, ch) ?: throw ProviderException("视频过大，网页预览不提供直链，请在 Telegram 中打开")
        return HGSeriesDetail(seriesId = id, title = s.title, cover = s.cover, desc = p.text, author = c, onlineTime = p.date, totalEps = 1,
            episodes = listOf(HGEpisode(1, id, s.duration, s.title, page = p.media[i].video)), episodeLabel = "频道视频", source = "Telegram")
    }
    fun resolve(client: TGClient, ep: HGEpisode): HGResolvedVideo {
        val (c, msg, i) = parts(ep.vid) ?: throw ProviderException.invalid("频道视频 id 无效")
        // CDN links carry an expiring token: refetch the post for a fresh one (falls back to the cached link)
        val url = runCatching { client.post(c, msg)?.media?.getOrNull(i)?.video }.getOrNull() ?: ep.page ?: throw ProviderException.upstream("视频地址为空")
        val st = HGStream("tg", "原画", url, kind = HGStreamKind.mp4Clear, headers = mapOf("User-Agent" to TGClient.UA))
        return HGResolvedVideo(ep.vid, url, streams = listOf(st))
    }
}
