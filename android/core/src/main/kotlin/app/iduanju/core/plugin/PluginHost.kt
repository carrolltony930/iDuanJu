package app.iduanju.core.plugin

import app.iduanju.core.util.NetGuard
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Host side of idj1 (iOS PluginHostContext + PluginNet + PluginHTML + PluginCrypto), engine-agnostic:
 * the JS engine calls `__host(name, argsJSON)` → [handle] → JSON string (or throws [PluginException]).
 * Network: allow-listed domains only, http/https on 80/443, no IP literals / private ranges, every redirect hop checked,
 * cookies from the plugin's own jar, ≤60 requests per call, ≤120 per minute, ≤20 MB bodies.
 */
class PluginHost(
    val manifest: PluginManifest,
    private val dir: File,
    private val settings: Map<String, String> = emptyMap(),
    extraDomains: List<String> = emptyList(),
    private val secrets: (String) -> String? = { null },
    val log: (String) -> Unit = {},
) {
    val id get() = manifest.id
    private val policy = DomainPolicy(manifest.httpDomains + extraDomains)
    private val cookies = HashMap<String, MutableList<Cookie>>()
    private val jar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) { cookies.values.flatten().filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this@PluginHost.cookies) {
            for (c in cookies) { val l = this@PluginHost.cookies.getOrPut(c.domain) { ArrayList() }; l.removeAll { it.name == c.name && it.path == c.path }; l.add(c) }
        }
    }
    private val baseHttp = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()
    private var requestsThisCall = 0
    private val window = ArrayDeque<Long>()
    private val html = HashMap<Int, Element>()
    private var nextNode = 1
    private val kv: JSONObject = try { JSONObject(File(dir, "storage.json").readText()) } catch (_: Exception) { JSONObject() }
    @Volatile var learnedUA: String? = null
    /** Test hook: request → (status, headers, body). */
    @Volatile var fixture: ((String, String) -> Triple<Int, Map<String, String>, ByteArray>?)? = null

    fun beginCall() { requestsThisCall = 0 }
    fun endCall() { synchronized(html) { html.clear(); nextNode = 1 } }

    private fun perm(m: String) = PluginException("E_PERMISSION", m)

    fun check(u: String): HttpUrl {
        val url = u.toHttpUrlOrNull() ?: throw perm("地址无效")
        if (url.scheme != "https" && url.scheme != "http") throw perm("只允许 http/https：${u.take(80)}")
        val host = url.host.lowercase()
        if (NetGuard.isIPLiteral(host)) throw perm("不允许直接访问 IP：$host")
        if (url.port != 443 && url.port != 80) throw perm("不允许非标准端口：$host:${url.port}")
        if (!policy.allows(host)) throw perm("域名未授权：$host")
        if (fixture == null && NetGuard.resolvesToBlocked(host)) throw perm("$host 解析到本机/内网地址，已拦截")
        return url
    }
    fun allowed(u: String) = try { check(u); true } catch (_: Exception) { false }

    private fun rateLimit() {
        val now = System.currentTimeMillis()
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() > 60_000) window.removeFirst()
            if (window.size >= PER_MINUTE) throw PluginException("E_RATE_LIMIT", "每分钟最多 $PER_MINUTE 个请求")
            window.addLast(now)
        }
    }

    private fun secretsIn(s: String): String {
        if (!s.contains("@@secret:")) return s
        var r = s
        for (st in manifest.settings) if (st.type == "secret") r = r.replace("@@secret:${st.key}@@", secrets(st.key) ?: "")
        return r
    }

    fun handle(name: String, argsJSON: String): String? {
        val a = try { JSONTokener(argsJSON).nextValue() as? JSONObject } catch (_: Exception) { null } ?: JSONObject()
        fun str(k: String) = if (a.has(k) && !a.isNull(k)) a.get(k).toString() else ""
        fun out(v: Any?): String = when (v) {
            null -> "null"; is String -> JSONObject.quote(v); is Boolean, is Number -> v.toString()
            is JSONObject, is JSONArray -> v.toString()
            is Map<*, *> -> JSONObject(v).toString(); is List<*> -> JSONArray(v).toString()
            else -> JSONObject.quote(v.toString())
        }
        return when (name) {
            "host.version" -> out(JSONObject().put("app", PluginManifest.APP_VERSION).put("spec", "idj1"))
            "host.has" -> out(str("cap") in setOf("http", "html", "crypto.hash", "crypto.hmac", "crypto.aes", "crypto.rsa", "codec", "m3u8", "storage", "settings", "url"))
            "http.request" -> out(httpRequest(a))
            "http.allowed" -> out(allowed(str("url")))
            "html.parse" -> out(htmlParse(str("text"), str("base")))
            "html.select" -> out(JSONArray(htmlSelect(a.optInt("id"), str("q"), a.optBoolean("first"))))
            "html.text" -> out(node(a.optInt("id")).text())
            "html.html" -> out(node(a.optInt("id")).html())
            "html.outer" -> out(node(a.optInt("id")).outerHtml())
            "html.attr" -> out(node(a.optInt("id")).let { n -> val k = str("k"); if (k.startsWith("abs:")) n.absUrl(k.removePrefix("abs:")) else n.attr(k) })
            "url.resolve" -> out(try { URI(str("base")).resolve(str("rel").replace(" ", "%20")).toString() } catch (_: Exception) { str("rel") })
            "crypto.hash" -> out(enc(hash(str("alg"), dec(str("data"), a.optString("in", "utf8"))), a.optString("out", "hex")))
            "crypto.hmac" -> out(enc(hmac(str("alg"), dec(str("key"), a.optString("keyEncoding", "utf8")), dec(str("data"), a.optString("in", "utf8"))), a.optString("out", "hex")))
            "crypto.aes" -> {
                val e = str("op") == "encrypt"; val kenc = a.optString("keyEncoding", "utf8")
                val iv = if (a.has("iv") && !a.isNull("iv")) dec(str("iv"), a.optString("ivEncoding", kenc)) else null
                val data = dec(str("data"), a.optString("in", if (e) "utf8" else "base64"))
                out(enc(aes(e, a.optString("mode", "cbc"), dec(str("key"), kenc), iv, data, a.optString("padding", "pkcs7") != "none"), a.optString("out", if (e) "base64" else "utf8")))
            }
            "crypto.rsa" -> {
                val pk = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pemBytes(str("publicKeyPem"))))
                val data = dec(str("data"), a.optString("in", "utf8"))
                if (str("op") == "verify") {
                    val alg = when (a.optString("hash", "sha256")) { "sha1" -> "SHA1withRSA"; "sha512" -> "SHA512withRSA"; else -> "SHA256withRSA" }
                    val s = java.security.Signature.getInstance(alg); s.initVerify(pk); s.update(data)
                    out(s.verify(dec(str("signature"), "base64")))
                } else {
                    val c = Cipher.getInstance(if (str("padding") == "oaep") "RSA/ECB/OAEPWithSHA-1AndMGF1Padding" else "RSA/ECB/PKCS1Padding")
                    c.init(Cipher.ENCRYPT_MODE, pk); out(enc(c.doFinal(data), a.optString("out", "base64")))
                }
            }
            "crypto.random" -> out(ByteArray(a.optInt("n", 16).coerceIn(1, 1024)).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })
            "codec.convert" -> out(enc(dec(str("data"), a.optString("from", "utf8")), a.optString("to", "hex")))
            "codec.gunzip" -> out(String(GZIPInputStream(ByteArrayInputStream(dec(str("data"), "base64"))).readBytes(), Charsets.UTF_8))
            "codec.unpack" -> out(JSUnpacker.unpack(str("js")) ?: "")
            "m3u8.parse" -> out(m3u8(str("text"), str("base")))
            "storage.get" -> { needStorage(); out(synchronized(kv) { kv.opt(str("k")) }) }
            "storage.set", "storage.remove" -> {
                needStorage()
                synchronized(kv) {
                    val k = str("k"); val prev = kv.opt(k)
                    if (name == "storage.remove" || !a.has("v") || a.isNull("v")) kv.remove(k) else kv.put(k, a.get("v"))
                    val data = kv.toString()
                    if (data.toByteArray().size > STORAGE_LIMIT) { if (prev == null) kv.remove(k) else kv.put(k, prev); throw PluginException("E_STORAGE_FULL", "插件存储超过 1MB") }
                    dir.mkdirs(); File(dir, "storage.json").writeText(data)
                }
                null
            }
            "settings.all" -> {
                val o = JSONObject(); settings.forEach { (k, v) -> o.put(k, v) }
                for (s in manifest.settings) { if (s.type == "secret") o.put(s.key, "@@secret:${s.key}@@"); if (!o.has(s.key)) o.put(s.key, s.default ?: "") }
                out(o)
            }
            "log" -> { log("📝 " + str("m").take(500)); null }
            else -> throw PluginException("E_UNSUPPORTED", "未知的宿主能力 $name")
        }
    }

    private fun needStorage() { if (!manifest.storage) throw PluginException("E_PERMISSION", "manifest 没有声明 permissions.storage") }

    // ---- http ----
    private fun httpRequest(a: JSONObject): JSONObject {
        if (++requestsThisCall > MAX_REQUESTS_PER_CALL) throw PluginException("E_RATE_LIMIT", "单次调用最多 $MAX_REQUESTS_PER_CALL 个请求")
        var url = check(secretsIn(a.optString("url")))
        val method = a.optString("method", "GET").uppercase()
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = learnedUA ?: DEFAULT_UA
        headers["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.7"
        a.optJSONObject("headers")?.let { h -> for (k in h.keys()) headers[k] = secretsIn(h.get(k).toString()) }
        headers.keys.filter { it.lowercase() in FORBIDDEN_HEADERS }.forEach { headers.remove(it) }
        var body: ByteArray? = null; var ctype: String? = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        if (a.has("body") && !a.isNull("body")) {
            val b = a.get("body")
            when (a.optString("bodyType", if (b is String) "text" else "json")) {
                "json" -> { body = b.toString().toByteArray(); if (ctype == null) ctype = "application/json" }
                "form" -> {
                    val o = b as? JSONObject ?: JSONObject()
                    body = o.keys().asSequence().sorted().joinToString("&") { k -> enc(k) + "=" + enc(secretsIn(o.get(k).toString())) }.toByteArray()
                    if (ctype == null) ctype = "application/x-www-form-urlencoded"
                }
                "base64" -> body = dec(b.toString(), "base64")
                else -> body = secretsIn(b.toString()).toByteArray()
            }
        }
        val timeout = (a.optInt("timeout", 20_000) / 1000L).coerceIn(1, 60)
        val follow = a.optString("redirect", "follow") != "manual"
        val respType = a.optString("responseType", "text")
        val client = baseHttp.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build()
        var hops = 0; var m = method; var bd = body
        while (true) {
            rateLimit()
            val start = System.currentTimeMillis()
            val (status, hdrs, data) = fixture?.invoke(m, url.toString()) ?: run {
                val rb = Request.Builder().url(url)
                headers.forEach { (k, v) -> rb.header(k, v) }
                if (bd != null || m == "POST" || m == "PUT" || m == "PATCH") rb.method(m, (bd ?: ByteArray(0)).toRequestBody(ctype?.toMediaTypeOrNull())) else rb.method(m, null)
                try {
                    client.newCall(rb.build()).execute().use { r ->
                        val bytes = r.body?.byteStream()?.use { s -> readCapped(s, MAX_BODY + 1) } ?: ByteArray(0)
                        if (bytes.size > MAX_BODY) throw PluginException("E_NETWORK", "响应超过 20MB")
                        Triple(r.code, r.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") }, bytes)
                    }
                } catch (e: PluginException) { throw e } catch (e: java.io.InterruptedIOException) {
                    log("✕ $m ${url.host}${url.encodedPath} · 超时"); throw PluginException("E_TIMEOUT", "${url.host}：${e.message}")
                } catch (e: Exception) { log("✕ $m ${url.host}${url.encodedPath} · ${e.message}"); throw PluginException("E_NETWORK", "${url.host}：${e.message}") }
            }
            log("$status $m ${url.host}${url.encodedPath} · ${data.size}B · ${System.currentTimeMillis() - start}ms")
            val loc = hdrs["location"]
            if (follow && status in 300..399 && loc != null && hops < 10) {
                val next = url.resolve(loc) ?: throw PluginException("E_NETWORK", "跳转地址无效")
                try { check(next.toString()) } catch (e: PluginException) { log("✕ 跳转被拦截 ${url.host} → ${next.host}"); throw PluginException("E_PERMISSION", "跳转到未授权地址：${next.host}") }
                url = next; hops++
                if (status == 303 || ((status == 301 || status == 302) && m == "POST")) { m = "GET"; bd = null }
                continue
            }
            val text = if (respType != "base64") decodeText(data, hdrs["content-type"]) else null
            val o = JSONObject().put("status", status).put("url", url.toString()).put("headers", JSONObject(hdrs))
            o.put("body", when {
                text == null -> Base64.getEncoder().encodeToString(data)
                respType == "json" -> try { JSONTokener(text).nextValue() } catch (_: Exception) { JSONObject.NULL }
                else -> text
            })
            return o
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    // ---- html (jsoup; node handles valid for one call) ----
    private fun register(e: Element): Int = synchronized(html) {
        if (html.size >= MAX_NODES) throw PluginException("E_MEMORY", "节点太多")
        val i = nextNode++; html[i] = e; i
    }
    private fun node(i: Int): Element = synchronized(html) { html[i] } ?: throw PluginException("E_INTERNAL", "节点已失效")
    private fun htmlParse(text: String, base: String): Int = register(Jsoup.parse(text, base))
    private fun htmlSelect(i: Int, q: String, first: Boolean): List<Int> {
        val n = node(i)
        val r = try { if (first) listOfNotNull(n.selectFirst(q)) else n.select(q).toList() } catch (e: Exception) { throw PluginException("E_PARSE", "选择器不合法：$q") }
        return r.map { register(it) }
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
        const val MAX_REQUESTS_PER_CALL = 60
        const val PER_MINUTE = 120
        const val MAX_BODY = 20 * 1024 * 1024
        const val STORAGE_LIMIT = 1 shl 20
        const val MAX_NODES = 300_000
        val FORBIDDEN_HEADERS = setOf("cookie", "cookie2", "host", "authorization", "proxy-authorization", "connection",
            "content-length", "transfer-encoding", "te", "upgrade", "proxy-connection", "keep-alive")

        fun fail(m: String) = PluginException("E_INTERNAL", m)

        /** InputStream.readNBytes is API 33+; Android 11 needs this. */
        fun readCapped(s: java.io.InputStream, cap: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            while (out.size() < cap) { val n = s.read(buf, 0, minOf(buf.size, cap - out.size())); if (n < 0) break; out.write(buf, 0, n) }
            return out.toByteArray()
        }

        fun dec(s: String, e: String?): ByteArray = when ((e ?: "utf8").lowercase()) {
            "utf8", "text" -> s.toByteArray(Charsets.UTF_8)
            "hex" -> { val h = s.lowercase(); if (h.length % 2 != 0 || h.any { it !in "0123456789abcdef" }) throw fail("hex 不合法"); ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            "base64" -> try { Base64.getDecoder().decode(s.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }) } catch (_: Exception) { throw fail("base64 不合法") }
            "latin1" -> s.toByteArray(Charsets.ISO_8859_1)
            else -> throw fail("不支持的编码 $e")
        }
        fun enc(d: ByteArray, e: String?): String = when ((e ?: "utf8").lowercase()) {
            "hex" -> d.joinToString("") { "%02x".format(it) }
            "base64" -> Base64.getEncoder().encodeToString(d)
            "utf8", "text" -> String(d, Charsets.UTF_8)
            "latin1" -> String(d, Charsets.ISO_8859_1)
            else -> throw fail("不支持的编码 $e")
        }
        fun hash(alg: String, d: ByteArray): ByteArray = MessageDigest.getInstance(when (alg.lowercase()) {
            "md5" -> "MD5"; "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; "sha384" -> "SHA-384"; "sha512" -> "SHA-512"; else -> throw fail("不支持的哈希 $alg")
        }).digest(d)
        fun hmac(alg: String, key: ByteArray, d: ByteArray): ByteArray {
            val n = when (alg.lowercase()) { "md5" -> "HmacMD5"; "sha1" -> "HmacSHA1"; "sha256" -> "HmacSHA256"; "sha512" -> "HmacSHA512"; else -> throw fail("不支持的 HMAC $alg") }
            return Mac.getInstance(n).apply { init(SecretKeySpec(key, n)) }.doFinal(d)
        }
        fun aes(encrypt: Boolean, mode: String, key: ByteArray, iv: ByteArray?, data: ByteArray, padding: Boolean): ByteArray {
            if (key.size !in setOf(16, 24, 32)) throw fail("AES key 长度必须是 16/24/32 字节")
            val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            val k = SecretKeySpec(key, "AES")
            return try {
                when (mode.lowercase()) {
                    "gcm" -> { if (iv == null) throw fail("GCM 需要 iv"); Cipher.getInstance("AES/GCM/NoPadding").apply { init(op, k, GCMParameterSpec(128, iv)) }.doFinal(data) }
                    "ctr" -> { if (iv?.size != 16) throw fail("CTR 需要 16 字节 iv"); Cipher.getInstance("AES/CTR/NoPadding").apply { init(op, k, IvParameterSpec(iv)) }.doFinal(data) }
                    "cbc" -> { if (iv?.size != 16) throw fail("CBC 需要 16 字节 iv"); Cipher.getInstance("AES/CBC/" + if (padding) "PKCS5Padding" else "NoPadding").apply { init(op, k, IvParameterSpec(iv)) }.doFinal(data) }
                    "ecb" -> Cipher.getInstance("AES/ECB/" + if (padding) "PKCS5Padding" else "NoPadding").apply { init(op, k) }.doFinal(data)
                    else -> throw fail("不支持的 AES 模式 $mode")
                }
            } catch (e: PluginException) { throw e } catch (e: javax.crypto.IllegalBlockSizeException) { throw fail("数据长度不是 16 的倍数（检查 padding）") }
            catch (e: Exception) { throw fail("AES 失败 ${e.message}") }
        }
        fun pemBytes(pem: String): ByteArray = Base64.getDecoder().decode(pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim())

        fun decodeText(d: ByteArray, contentType: String?): String {
            val ct = (contentType ?: "").lowercase()
            val cs = when { ct.contains("gbk") || ct.contains("gb2312") || ct.contains("gb18030") -> "GB18030"; ct.contains("big5") -> "Big5"; else -> null }
            return if (cs != null) String(d, Charset.forName(cs)) else String(d, Charsets.UTF_8)
        }

        fun m3u8(text: String, base: String): JSONObject {
            fun abs(s: String) = try { if (base.isEmpty()) s else URI(base).resolve(s.replace(" ", "%20")).toString() } catch (_: Exception) { s }
            fun attrs(line: String): Map<String, String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            val variants = JSONArray(); val segments = JSONArray(); var key: JSONObject? = null
            var pv: Map<String, String>? = null; var pd: Double? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> pv = attrs(line.drop(18))
                    line.startsWith("#EXTINF:") -> pd = line.drop(8).split(",").first().toDoubleOrNull()
                    line.startsWith("#EXT-X-KEY:") -> { val at = attrs(line.drop(11)); key = JSONObject().put("method", at["METHOD"] ?: "").put("uri", at["URI"]?.let(::abs) ?: "").put("iv", at["IV"] ?: "") }
                    !line.startsWith("#") && line.isNotEmpty() -> {
                        val v = pv
                        if (v != null) { variants.put(JSONObject().put("url", abs(line)).put("bandwidth", v["BANDWIDTH"]?.toIntOrNull() ?: 0).put("resolution", v["RESOLUTION"] ?: "").put("name", v["NAME"] ?: "")); pv = null }
                        else { segments.put(JSONObject().put("url", abs(line)).put("duration", pd ?: 0.0)); pd = null }
                    }
                }
            }
            return JSONObject().put("variants", variants).put("segments", segments).also { o -> key?.let { o.put("key", it) } }
        }
    }
}

/** Dean Edwards packer unpacker — pure string substitution, never runs remote JS (iOS JSUnpacker). */
object JSUnpacker {
    fun unpack(script: String): String? {
        val m = Regex("eval\\(function\\(p,a,c,k,e,d\\)\\{.*?\\}\\('(.*?)',\\s*(\\d+),\\s*(\\d+),\\s*'([^']*)'\\s*\\.split\\('\\|'\\)", RegexOption.DOT_MATCHES_ALL).find(script) ?: return null
        val a = m.groupValues[2].toIntOrNull() ?: return null; val c = m.groupValues[3].toIntOrNull() ?: return null
        if (a <= 1 || a > 62 || c < 0 || c > 200_000) return null
        val keys = m.groupValues[4].split("|")
        val lookup = HashMap<String, String>()
        for (i in 0 until c) { val k = toBase(i, a); lookup[k] = if (i < keys.size && keys[i].isNotEmpty()) keys[i] else k }
        return Regex("\\b(\\w+)\\b").replace(m.groupValues[1]) { lookup[it.value] ?: it.value }.replace("\\'", "'")
    }
    private fun toBase(n0: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (n0 == 0) return "0"; var n = n0; val sb = StringBuilder()
        while (n > 0) { sb.insert(0, digits[n % base]); n /= base }
        return sb.toString()
    }
}
