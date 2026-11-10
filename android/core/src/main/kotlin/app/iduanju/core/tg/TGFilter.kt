package app.iduanju.core.tg

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.util.UUID

/** iOS TGRule: user blocklist rule over post text, inline-button text and link domains. */
data class TGRule(val kind: Kind, val pattern: String, val channel: String? = null, val enabled: Boolean = true, val id: String = UUID.randomUUID().toString()) {
    enum class Kind(val label: String) { keyword("关键词"), regex("正则"), domain("链接域名") }

    fun matches(p: TGPost): Boolean {
        val pat = pattern.trim()
        if (!enabled || pat.isEmpty()) return false
        if (channel != null && channel != p.channel.lowercase()) return false
        val hay = (listOf(p.text) + p.buttons.map { it.text } + listOf(p.preview?.title ?: "", p.preview?.desc ?: "")).joinToString("\n")
        return when (kind) {
            Kind.keyword -> TGFilter.fold(hay).contains(TGFilter.fold(pat))
            Kind.regex -> runCatching { Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(hay + "\n" + (p.links + p.buttons.map { it.url }).joinToString("\n")) }.getOrDefault(false)
            Kind.domain -> { val d = pat.lowercase().replace("*.", ""); TGFilter.linkDomains(p).any { it == d || it.endsWith(".$d") } }
        }
    }

    fun toJSON(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("pattern", pattern).put("channel", channel).put("enabled", enabled)
    companion object {
        fun fromJSON(o: JSONObject) = TGRule(runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.keyword), o.optString("pattern"),
            o.optString("channel").ifEmpty { null }?.takeIf { !o.isNull("channel") }, o.optBoolean("enabled", true), o.optString("id").ifEmpty { UUID.randomUUID().toString() })
    }
}

enum class TGFilterMode(val label: String) { hide("直接隐藏"), fold("折叠为「已屏蔽」") }
data class TGVerdict(val blocked: Boolean, val reason: String) { companion object { val pass = TGVerdict(false, "") } }

/** iOS TGFilter: built-in ad heuristics + user rules (same word lists and scoring). */
object TGFilter {
    val adTags = listOf("#广告", "#推广", "#赞助", "#ad", "#ads", "#promo", "#sponsored", "【广告】", "[广告]", "（广告）", "(广告)",
        "广告合作", "商务合作", "投放广告", "广告投放", "sponsored", "advertisement")
    val spamWords = listOf("博彩", "娱乐城", "百家乐", "真人荷官", "体育投注", "首充", "彩金", "返水", "棋牌", "赌场", "老虎机", "电子游艺",
        "usdt", "担保交易", "日赚", "躺赚", "跑分", "代收代付", "网赚", "引流", "接单", "实体店招商", "开户", "存款", "提款秒到",
        "注册送", "注册即送", "充值", "返利", "佣金")
    val callToAction = listOf("点击", "立即", "下载", "注册", "入口", "官网", "进群", "加群", "联系", "咨询", "福利", "优惠", "领取", "免费领", "扫码",
        "join", "click", "download", "register", "sign up", "bonus")

    /** Case-, width- and diacritic-insensitive form (iOS .caseInsensitive/.diacriticInsensitive/.widthInsensitive). */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun host(u: String) = runCatching { URI(u.trim()).host?.lowercase() }.getOrNull()
    fun linkDomains(p: TGPost): List<String> = (p.links + p.buttons.map { it.url } + listOf(p.preview?.url ?: "")).mapNotNull(::host)

    fun isBotLink(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val h = u.host?.lowercase() ?: return false
        if (h != "t.me" && h != "telegram.me") return false
        val start = u.rawQuery?.split("&")?.any { it.startsWith("start=") && it.length > 6 } ?: false
        val name = u.path.trim('/').substringBefore('/')
        return start && name.lowercase().endsWith("bot")
    }

    fun isOwn(url: String, channel: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return true
        val h = u.host?.lowercase() ?: return true
        if (h == "t.me" || h == "telegram.me") {
            val first = u.path.split("/").firstOrNull { it.isNotEmpty() }?.lowercase() ?: ""
            return first == channel || first == "s" || first.isEmpty()
        }
        return false
    }

    /** Score-based check; ≥ 3 points = ad. */
    fun heuristic(p: TGPost): TGVerdict {
        if (p.isService) return TGVerdict.pass
        val lower = p.text.lowercase()
        var score = 0; val why = ArrayList<String>()
        adTags.firstOrNull { lower.contains(it.lowercase()) }?.let { score += 3; why.add("广告标记 $it") }
        val spam = spamWords.filter { lower.contains(it) }
        if (spam.size >= 2) { score += 3; why.add("推广词 " + spam.take(3).joinToString("/")) } else if (spam.size == 1) score += 1
        val cta = callToAction.any { lower.contains(it) }
        val own = p.channel.lowercase()
        val external = p.buttons.filter { !isOwn(it.url, own) && !isBotLink(it.url) }
        if (external.isNotEmpty()) { score += if (cta || spam.isNotEmpty()) 3 else 1; why.add("外链按钮 ${external.size} 个") }
        val invites = (p.links + p.buttons.map { it.url }).filter { it.contains("t.me/+") || it.contains("joinchat") || it.contains("t.me/addlist") }
        val foreign = p.links.filter { !isOwn(it, own) }
        val stripped = p.text.replace(Regex("https?://\\S+|t\\.me/\\S+|@[A-Za-z0-9_]{4,}"), "").trim()
        if (p.media.isEmpty() && (foreign.isNotEmpty() || invites.isNotEmpty()) && stripped.length < 60) {
            score += if (cta || invites.isNotEmpty()) 3 else 2; why.add("纯推广链接")
        }
        if (invites.isNotEmpty() && cta) score += 1
        if ((lower.contains("置顶") || lower.contains("pinned")) && (foreign.isNotEmpty() || external.isNotEmpty())) { score += 2; why.add("置顶推广") }
        return if (score >= 3) TGVerdict(true, why.joinToString(" · ")) else TGVerdict.pass
    }

    fun verdict(p: TGPost, rules: List<TGRule>, heuristics: Boolean, channelAdBlock: Boolean = true): TGVerdict {
        rules.firstOrNull { it.matches(p) }?.let { return TGVerdict(true, "规则 ${it.kind.label}：${it.pattern}") }
        return if (heuristics && channelAdBlock) heuristic(p) else TGVerdict.pass
    }

    /** 「加入屏蔽」 suggestions: link domains, button texts, hashtags, first distinctive line. */
    fun suggestions(p: TGPost): List<TGRule> {
        val out = ArrayList<TGRule>()
        linkDomains(p).toSet().filter { it != "t.me" && it != "telegram.me" }.forEach { out.add(TGRule(TGRule.Kind.domain, it)) }
        p.buttons.filter { it.text.isNotEmpty() }.forEach { out.add(TGRule(TGRule.Kind.keyword, it.text)) }
        Regex("#([\\p{L}\\p{N}_]{2,20})").findAll(p.text).take(4).forEach { out.add(TGRule(TGRule.Kind.keyword, "#" + it.groupValues[1])) }
        p.text.split("\n").map { it.trim() }.firstOrNull { it.length >= 4 }?.let { out.add(TGRule(TGRule.Kind.keyword, it.take(16))) }
        val seen = HashSet<String>()
        return out.filter { seen.add(it.kind.name + it.pattern.lowercase()) }
    }

    data class Bundle(val mode: TGFilterMode, val heuristics: Boolean, val rules: List<TGRule>)

    fun export(b: Bundle): String = JSONObject().put("version", 1).put("mode", b.mode.name).put("heuristics", b.heuristics)
        .put("rules", JSONArray(b.rules.map { it.toJSON() })).toString(2)

    /** JSON export, or plain lines (`keyword`, `re:pattern`, `domain:example.com`). */
    fun importRules(text: String): Bundle? {
        runCatching {
            val o = JSONObject(text)
            val a = o.getJSONArray("rules")
            return Bundle(runCatching { TGFilterMode.valueOf(o.optString("mode")) }.getOrDefault(TGFilterMode.fold), o.optBoolean("heuristics", true),
                (0 until a.length()).map { TGRule.fromJSON(a.getJSONObject(it)) })
        }
        val rules = text.lines().mapNotNull { l ->
            val s = l.trim()
            when {
                s.isEmpty() || s.startsWith("//") -> null
                s.startsWith("re:") -> TGRule(TGRule.Kind.regex, s.drop(3))
                s.startsWith("domain:") -> TGRule(TGRule.Kind.domain, s.drop(7))
                else -> TGRule(TGRule.Kind.keyword, s)
            }
        }
        return if (rules.isEmpty()) null else Bundle(TGFilterMode.fold, true, rules)
    }
}
