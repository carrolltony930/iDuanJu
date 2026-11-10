package app.iduanju.core.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginHostTest {
    private val manifestJSON = """{"schema":1,"id":"com.example.test.video","name":"Test","version":"1.0.0","author":{"name":"t"},
        "type":"video","adult":false,"minAppVersion":"1.0","entry":"main.js","permissions":{"http":["*.example.com"],"media":["cdn.example.net"],"storage":true},
        "capabilities":["home","detail","play"],"settings":[{"key":"token","name":"T","type":"secret"},{"key":"lang","name":"L","type":"select","default":"zh","options":["zh","en"]}]}"""

    private fun host(): PluginHost {
        val m = PluginManifest.decode(manifestJSON)
        return PluginHost(m, Files.createTempDirectory("plg").toFile(), secrets = { if (it == "token") "S3CRET" else null })
    }

    @Test fun manifestAndBundle() {
        val m = PluginManifest.decode(manifestJSON)
        assertEquals(emptyList<String>(), m.problems())
        val bad = PluginManifest.decode(manifestJSON.replace("\"com.example.test.video\"", "\"Bad\"").replace("\"*.example.com\"", "\"1.2.3.4\""))
        assertTrue(bad.problems().any { it.startsWith("id") }); assertTrue(bad.problems().any { it.startsWith("域名格式不对") })
        val noPlay = PluginManifest.decode(manifestJSON.replace("\"play\"]", "\"search\"]"))
        assertTrue(noPlay.problems().contains("type=video 必须实现 play"))
        try { PluginManifest.decode(JSONObject(manifestJSON).put("compat", "tvbox")); fail() } catch (e: PluginException) { assertTrue(e.message!!.contains("idj1")) }
        val b = PluginBundle.parse(JSONObject().put("manifest", JSONObject(manifestJSON)).put("files", JSONObject().put("main.js", "export default { home() {} }")).toString())
        assertTrue(PluginGlue.script(b.source).contains("globalThis.__plugin = { home"))
        try { PluginGlue.script("import x from 'y'\nexport default {}"); fail() } catch (_: PluginException) {}
        // every bundled iOS plugin must parse + validate
        File("../../../priv/STV/Resources/Plugins").takeIf { it.isDirectory }?.listFiles { f -> f.name.endsWith(".idjplugin") }?.forEach {
            val pb = PluginBundle.parse(it.readText()); PluginGlue.script(pb.source); println("bundled ok: ${pb.manifest.id} ${pb.manifest.type}")
        }
    }

    @Test fun domainPolicy() {
        val p = DomainPolicy(listOf("*.example.com", "exact.org"))
        assertTrue(p.allows("example.com")); assertTrue(p.allows("a.b.example.com")); assertTrue(p.allows("exact.org"))
        assertFalse(p.allows("www.exact.org")); assertFalse(p.allows("badexample.com")); assertFalse(p.allows("example.com.evil.net"))
        val h = host()
        assertFalse(h.allowed("https://evil.net/")); assertFalse(h.allowed("ftp://www.example.com/")); assertFalse(h.allowed("https://www.example.com:8443/"))
        assertFalse(h.allowed("https://127.0.0.1/"))
    }

    @Test fun cryptoCodecHtmlM3u8() {
        val h = host()
        fun call(n: String, a: JSONObject) = h.handle(n, a.toString())
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", call("crypto.hash", JSONObject().put("alg", "md5").put("data", "hello")))
        assertEquals("\"9307b3b915efb5171ff14d8cb55fbcc798c6c0ef1456d66ded1a6aa723a58b7b\"", call("crypto.hmac", JSONObject().put("alg", "sha256").put("key", "key").put("data", "hello")))
        val key = "k".repeat(16); val iv = "v".repeat(16)
        val ct = JSONArray("[" + call("crypto.aes", JSONObject().put("op", "encrypt").put("mode", "cbc").put("key", key).put("iv", iv).put("data", "秘密 text")) + "]").getString(0)
        assertEquals("\"秘密 text\"", call("crypto.aes", JSONObject().put("op", "decrypt").put("mode", "cbc").put("key", key).put("iv", iv).put("data", ct)))
        assertEquals("\"aGk=\"", call("codec.convert", JSONObject().put("data", "hi").put("from", "utf8").put("to", "base64")))
        assertEquals("\"6869\"", call("codec.convert", JSONObject().put("data", "hi")))
        val doc = call("html.parse", JSONObject().put("text", "<div class=a><a href='/x/1'>One</a><a href='/x/2'>Two</a></div>").put("base", "https://www.example.com/list"))!!.toInt()
        val links = JSONArray(call("html.select", JSONObject().put("id", doc).put("q", "div.a a")))
        assertEquals(2, links.length())
        assertEquals("\"Two\"", call("html.text", JSONObject().put("id", links.getInt(1))))
        assertEquals("\"/x/1\"", call("html.attr", JSONObject().put("id", links.getInt(0)).put("k", "href")))
        assertEquals("\"https://www.example.com/x/1\"", call("html.attr", JSONObject().put("id", links.getInt(0)).put("k", "abs:href")))
        assertEquals("\"https://www.example.com/a/b.m3u8\"", call("url.resolve", JSONObject().put("base", "https://www.example.com/a/index.html").put("rel", "b.m3u8")))
        val m3 = JSONObject(call("m3u8.parse", JSONObject().put("text", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720\nmid/index.m3u8\n").put("base", "https://cdn.example.net/v/master.m3u8")))
        assertEquals("https://cdn.example.net/v/mid/index.m3u8", m3.getJSONArray("variants").getJSONObject(0).getString("url"))
        val packed = "eval(function(p,a,c,k,e,d){e=function(c){return c};return p}('0 1=\\'2\\'',3,3,'var|src|https'.split('|'),0,{}))"
        assertEquals("\"var src='https'\"", call("codec.unpack", JSONObject().put("js", packed)))
        call("storage.set", JSONObject().put("k", "a").put("v", JSONObject().put("n", 1)))
        assertEquals("{\"n\":1}", call("storage.get", JSONObject().put("k", "a")))
        val st = JSONObject(call("settings.all", JSONObject())!!)
        assertEquals("@@secret:token@@", st.getString("token")); assertEquals("zh", st.getString("lang"))
        try { call("nope", JSONObject()); fail() } catch (e: PluginException) { assertEquals("E_UNSUPPORTED", e.code) }
    }

    @Test fun httpRedirectGuardAndSecrets() {
        val h = host()
        val seen = ArrayList<String>()
        h.fixture = { m, u -> seen += "$m $u"
            when {
                u.endsWith("/start") -> Triple(302, mapOf("location" to "https://cdn.example.com/next"), ByteArray(0))
                u.endsWith("/next") -> Triple(200, mapOf("content-type" to "application/json"), "{\"ok\":true}".toByteArray())
                u.endsWith("/leak") -> Triple(302, mapOf("location" to "https://evil.net/steal"), ByteArray(0))
                else -> null
            }
        }
        val r = JSONObject(h.handle("http.request", JSONObject().put("url", "https://www.example.com/start").put("responseType", "json").toString())!!)
        assertEquals(200, r.getInt("status")); assertEquals(true, r.getJSONObject("body").getBoolean("ok")); assertEquals("https://cdn.example.com/next", r.getString("url"))
        try { h.handle("http.request", JSONObject().put("url", "https://www.example.com/leak").toString()); fail() } catch (e: PluginException) { assertEquals("E_PERMISSION", e.code) }
        try { h.handle("http.request", JSONObject().put("url", "https://evil.net/").toString()); fail() } catch (e: PluginException) { assertEquals("E_PERMISSION", e.code) }
        assertFalse(seen.any { it.contains("evil.net") })
        h.beginCall()
        repeat(PluginHost.MAX_REQUESTS_PER_CALL) { runCatching { h.handle("http.request", JSONObject().put("url", "https://www.example.com/next").toString()) } }
        try { h.handle("http.request", JSONObject().put("url", "https://www.example.com/next").toString()); fail() } catch (e: PluginException) { assertEquals("E_RATE_LIMIT", e.code) }
    }
}
