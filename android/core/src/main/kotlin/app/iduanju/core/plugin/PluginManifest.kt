package app.iduanju.core.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** idj1 plugin manifest (docs/plugins/SPEC_ZH.md) — 1:1 with iOS PluginManifest. Only idj1 is accepted. */
enum class PluginType(val label: String, val required: List<String>) {
    video("长视频", listOf("detail", "play")), shortdrama("短剧", listOf("detail", "play")),
    comic("漫画", listOf("detail", "pages")), live("直播", listOf("play"));
}

class PluginException(val code: String, message: String) : Exception(message) {
    fun json(): String = JSONObject().put("code", code).put("message", message ?: "").toString()
    val friendly: String get() = describe(code, message ?: "")
    companion object {
        fun invalidManifest(p: List<String>) = PluginException("E_INSTALL", "插件格式不对：" + p.joinToString("；"))
        fun fromJSON(s: String): PluginException {
            val o = try { JSONObject(s) } catch (_: Exception) { JSONObject() }
            return PluginException(o.optString("code", "E_INTERNAL"), o.optString("message", s))
        }
        fun describe(code: String, m: String): String = when (code) {
            "E_CF_REQUIRED" -> "站点需要 Cloudflare 验证。点「去验证」完成一次后重试。"
            "E_PERMISSION" -> "插件访问被拦截：$m"
            "E_TIMEOUT" -> "插件执行超时（$m）"
            "E_MEMORY" -> "插件内存超限，已中断"
            "E_PARSE" -> "解析失败，源可能已改版，请更新插件：$m"
            "E_NOT_FOUND" -> "内容不存在：$m"
            "E_UNSUPPORTED" -> "这个插件不支持该操作"
            "E_NETWORK" -> "网络错误：$m"
            "E_HTTP" -> "站点返回错误：$m"
            "E_RATE_LIMIT" -> "请求太频繁，稍后再试：$m"
            "E_LOGIN_REQUIRED" -> "需要登录：$m"
            else -> "$m ($code)"
        }
    }
}

data class PluginSetting(val key: String, val name: String, val type: String, val default: String?, val options: List<String>)

data class PluginManifest(
    val schema: Int, val id: String, val name: String, val version: String, val authorName: String, val description: String?,
    val type: PluginType?, val typeRaw: String, val adult: Boolean, val minAppVersion: String, val entry: String,
    val http: List<String>, val media: List<String>?, val cloudflare: Boolean, val storage: Boolean, val login: Boolean,
    val capabilities: List<String>, val settings: List<PluginSetting>, val updateUrl: String?, val signatureAlg: String?,
    val raw: JSONObject,
) {
    val httpDomains get() = http
    val mediaDomains get() = media ?: http

    fun problems(appVersion: String = APP_VERSION): List<String> {
        val p = ArrayList<String>()
        if (schema != 1) p += "schema 必须为 1"
        if (id.length > 100 || !Regex("^[a-z0-9]+(\\.[a-z0-9-]+){2,}$").matches(id)) p += "id 必须是反向域名形式（小写），如 com.yourname.site"
        if (name.isBlank() || name.length > 40) p += "name 不能为空且不超过 40 字"
        if (semver(version) == null) p += "version 必须是 x.y.z"
        if (authorName.isBlank()) p += "author.name 不能为空"
        if (semver(minAppVersion, loose = true) == null) p += "minAppVersion 格式不对"
        else if (compare(minAppVersion, appVersion) > 0) p += "需要 App $minAppVersion 或更高版本（当前 $appVersion）"
        if (!Regex("^[A-Za-z0-9_-]+\\.js$").matches(entry)) p += "entry 必须是同目录下的 .js 文件名"
        if (http.isEmpty()) p += "permissions.http 至少声明一个域名"
        for (d in http + (media ?: emptyList())) if (DomainRule.parse(d) == null) p += "域名格式不对：$d（只允许 example.com 或 *.example.com）"
        if (type == null) p += "type 只能是 video/shortdrama/comic/live"
        type?.required?.forEach { if (it !in capabilities) p += "type=${type.name} 必须实现 $it" }
        for (s in settings) {
            if (!Regex("^[a-zA-Z][a-zA-Z0-9_]{0,31}$").matches(s.key)) p += "settings.key 不合法：${s.key}"
            if (s.type !in listOf("text", "select", "secret")) p += "settings.type 只能是 text/select/secret：${s.key}"
        }
        updateUrl?.takeIf { it.isNotEmpty() }?.let { if (!it.startsWith("https://")) p += "update.url 必须是 https" }
        if (signatureAlg != null && signatureAlg != "ed25519") p += "signature.alg 只支持 ed25519"
        return p
    }

    companion object {
        const val APP_VERSION = "1.5.2"

        fun decode(text: String): PluginManifest {
            val o = try { JSONObject(text) } catch (_: Exception) { throw PluginException.invalidManifest(listOf("manifest 不是合法 JSON")) }
            return decode(o)
        }

        fun decode(o: JSONObject): PluginManifest {
            o.optString("compat").takeIf { it.isNotEmpty() && it != "idj1" }?.let { throw PluginException.invalidManifest(listOf("不支持兼容模式 compat=$it：只接受 idj1 格式的插件")) }
            fun req(k: String): Any = if (o.has(k)) o.get(k) else throw PluginException.invalidManifest(listOf("manifest 缺少字段 $k"))
            fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
            val perms = req("permissions") as? JSONObject ?: throw PluginException.invalidManifest(listOf("manifest 字段类型不对：permissions"))
            val author = req("author") as? JSONObject ?: throw PluginException.invalidManifest(listOf("manifest 字段类型不对：author"))
            val typeRaw = req("type").toString()
            return PluginManifest(
                schema = (req("schema") as? Number)?.toInt() ?: throw PluginException.invalidManifest(listOf("manifest 字段类型不对：schema")),
                id = req("id").toString(), name = req("name").toString(), version = req("version").toString(),
                authorName = author.optString("name"), description = o.optString("description").ifEmpty { null },
                type = PluginType.entries.firstOrNull { it.name == typeRaw }, typeRaw = typeRaw,
                adult = (req("adult") as? Boolean) ?: false, minAppVersion = req("minAppVersion").toString(), entry = req("entry").toString(),
                http = strs(perms.optJSONArray("http")), media = perms.optJSONArray("media")?.let { strs(it) },
                cloudflare = perms.optBoolean("cloudflare"), storage = perms.optBoolean("storage"), login = perms.optBoolean("login"),
                capabilities = strs(req("capabilities") as? JSONArray),
                settings = (0 until (o.optJSONArray("settings")?.length() ?: 0)).map { i ->
                    val s = o.getJSONArray("settings").getJSONObject(i)
                    PluginSetting(s.optString("key"), s.optString("name"), s.optString("type"), s.optString("default").ifEmpty { null }, strs(s.optJSONArray("options")))
                },
                updateUrl = o.optJSONObject("update")?.optString("url"),
                signatureAlg = o.optJSONObject("integrity")?.optJSONObject("signature")?.optString("alg"),
                raw = o,
            )
        }

        fun semver(v: String, loose: Boolean = false): List<Int>? {
            val parts = v.split(".").map { it.toIntOrNull() }
            if (parts.any { it == null }) return null
            return if (if (loose) parts.size in 1..3 else parts.size == 3) parts.map { it!! } else null
        }
        fun compare(a: String, b: String): Int {
            val x = semver(a, true) ?: listOf(0); val y = semver(b, true) ?: listOf(0)
            for (i in 0 until maxOf(x.size, y.size)) { val l = x.getOrElse(i) { 0 }; val r = y.getOrElse(i) { 0 }; if (l != r) return if (l < r) -1 else 1 }
            return 0
        }
        fun fileHash(d: ByteArray) = "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(d))
    }
}

/** `example.com` (exact host) or `*.example.com` (the domain itself and any subdomain). */
data class DomainRule(val suffix: String, val wildcard: Boolean) {
    fun matches(host: String): Boolean { val h = host.lowercase(); return h == suffix || (wildcard && h.endsWith(".$suffix")) }
    companion object {
        private val re = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
        fun parse(raw: String): DomainRule? {
            val s = raw.lowercase().trim(); val wild = s.startsWith("*."); val d = if (wild) s.drop(2) else s
            if (!re.matches(d) || d.none { it.isLetter() }) return null
            return DomainRule(d, wild)
        }
    }
}

class DomainPolicy(list: List<String>) {
    val rules = list.mapNotNull { DomainRule.parse(it) }
    fun allows(host: String) = rules.any { it.matches(host) }
}

/** `.idjplugin` = JSON bundle `{ "manifest": {...}, "files": { "main.js": "..." } }`. */
data class PluginBundle(val manifest: PluginManifest, val files: Map<String, String>) {
    val source: String get() = files[manifest.entry] ?: throw PluginException("E_INSTALL", "安装失败：缺少入口文件 ${manifest.entry}")
    companion object {
        fun parse(text: String): PluginBundle {
            val o = try { JSONObject(text) } catch (_: Exception) { throw PluginException("E_INSTALL", "不是合法的 .idjplugin（需要 {\"manifest\":…, \"files\":{…}}）") }
            val m = PluginManifest.decode(o.optJSONObject("manifest") ?: throw PluginException("E_INSTALL", "不是合法的 .idjplugin（需要 {\"manifest\":…, \"files\":{…}}）"))
            val p = m.problems(); if (p.isNotEmpty()) throw PluginException.invalidManifest(p)
            val f = o.optJSONObject("files") ?: JSONObject()
            return PluginBundle(m, f.keys().asSequence().associateWith { f.optString(it) })
        }
    }
}
