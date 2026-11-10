package app.iduanju.debug

import android.util.Log
import app.iduanju.IdjApp
import app.iduanju.core.plugin.*
import app.iduanju.plugin.QuickJSEngine
import app.iduanju.ui.player.EpisodeCache
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Debug-only: exercises the real QuickJS engine + idj1 host. Logs under IDJ-E2E. */
object PluginSelfTest {
    private fun log(s: String) = Log.i("IDJ-E2E", s)

    private val manifest = JSONObject("""{"schema":1,"id":"com.example.selftest.video","name":"SelfTest","version":"1.0.0","author":{"name":"idj"},
        "type":"video","adult":false,"minAppVersion":"1.0","entry":"main.js","permissions":{"http":["*.example.com"],"storage":true},
        "capabilities":["home","detail","play","search"],"settings":[{"key":"lang","name":"L","type":"select","default":"zh","options":["zh","en"]}]}""")

    private const val SRC = """
const sleep = (n) => Promise.resolve(n);
export default {
  async home() {
    const doc = host.html.parse('<ul><li><a href="/v/1" data-c="https://img.example.com/1.jpg">一号</a></li><li><a href="/v/2">二号</a></li></ul>', 'https://www.example.com/');
    const items = doc.select('li a').map(a => ({ id: a.attr('abs:href'), title: a.text(), cover: a.attr('data-c'), remarks: host.crypto.md5(a.text()).slice(0, 6) }));
    await sleep(1);
    host.storage.set('n', (host.storage.get('n') || 0) + 1);
    return { categories: [{ id: 'all', name: '全部' }], items, hasMore: false, n: host.storage.get('n'), lang: host.settings.lang,
             aes: host.crypto.aes({ op: 'decrypt', mode: 'cbc', key: 'k'.repeat(16), iv: 'fedcba9876543210',
                    data: host.crypto.aes({ op: 'encrypt', mode: 'cbc', key: 'k'.repeat(16), iv: 'fedcba9876543210', data: 'ok-aes' }) }),
             b64: host.codec.base64Encode('hi'), allowed: host.http.allowed('https://evil.net/'), url: host.url.resolve('https://a.example.com/x/y', '../z') };
  },
  async detail({ id }) { return { title: 'T ' + id, lines: [{ name: 'L1', episodes: [{ id: id + '#1', name: 'E1' }] }] }; },
  async search({ keyword }) {
    try { await host.http.get('https://evil.net/x'); return { items: [] }; }
    catch (e) { return { items: [{ id: 'blocked', title: e.code + ':' + keyword }] }; }
  },
  async play() { throw host.error('E_NOT_FOUND', 'no stream'); }
};
"""

    fun run() {
        try {
            val dir = File(IdjApp.instance.cacheDir, "plugin-selftest").apply { deleteRecursively() }
            val bundle = PluginBundle.parse(JSONObject().put("manifest", manifest).put("files", JSONObject().put("main.js", SRC)).toString())
            val t0 = System.currentTimeMillis()
            val inst = PluginInstance(bundle, PluginHost(bundle.manifest, dir, log = { log("plugin: $it") }), ::QuickJSEngine)
            log("selftest engine up ${System.currentTimeMillis() - t0}ms has(home)=${inst.has("home")} has(pages)=${inst.has("pages")}")
            val home = inst.call("home", JSONObject()) as JSONObject
            log("selftest home=$home")
            val v = PluginVideo(inst)
            val page = v.list("", 1)
            log("selftest list items=${page.items.map { it.title + "|" + it.cover.ifEmpty { "-" } }} cats=${v.categories.map { it.name }}")
            val d = v.detail(page.items.first().seriesId)
            log("selftest detail title=${d.title} eps=${d.episodes.map { it.title + "@" + it.page }}")
            log("selftest search=${v.search("关键", 1).items.map { it.title }}")
            val err = runCatching { v.resolve(d.episodes.first()) }.exceptionOrNull()
            log("selftest play error=${(err as? PluginException)?.let { it.code + " " + it.message } ?: err}")
            val ok = home.getString("aes") == "ok-aes" && home.getString("b64") == "aGk=" && !home.getBoolean("allowed") && home.getInt("n") == 1 &&
                page.items.size == 2 && d.episodes.size == 1 && (err as? PluginException)?.code == "E_NOT_FOUND"
            inst.close()
            log(if (ok) "SELFTEST OK" else "SELFTEST FAIL")
        } catch (e: Throwable) { log("SELFTEST FAIL ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Evaluates [src] after the prelude in a throwaway engine (debugging the glue). */
    fun eval(src: String) {
        try {
            val e = QuickJSEngine()
            val bundle = PluginBundle.parse(JSONObject().put("manifest", manifest).put("files", JSONObject().put("main.js", SRC)).toString())
            val h = PluginHost(bundle.manifest, File(IdjApp.instance.cacheDir, "plugin-eval"))
            e.install { n, a -> try { h.handle(n, a) } catch (x: PluginException) { PluginGlue.ERR + x.json() } }
            e.eval(PluginGlue.prelude, "prelude.js")
            log("eval => " + e.eval(src, "eval.js"))
            e.close()
        } catch (x: Throwable) { log("eval FAIL ${x.javaClass.simpleName}: ${x.message}") }
    }

    fun live(path: String, q: String?) {
        try {
            val app = IdjApp.instance
            val rec = app.plugins.install(File(path).readText(), "e2e")
            log("plugin installed ${rec.id} type=${rec.type}")
            val v = app.plugins.video(rec.id)
            val t0 = System.currentTimeMillis()
            val page = if (q != null) v.search(q, 1) else v.list("", 1)
            log("plugin list items=${page.items.size} cats=${v.categories.size} first=${page.items.firstOrNull()?.title} (${System.currentTimeMillis() - t0}ms)")
            val s = page.items.first()
            val d = app.plugins.video(rec.id).detail(s.seriesId)
            log("plugin detail title=${d.title} eps=${d.episodes.size} line=${d.episodes.firstOrNull()?.page}")
            val ep = d.episodes.first()
            val r = v.resolve(ep)
            log("plugin play streams=${r.streams.size} kind=${r.streams.first().kind} host=${java.net.URI(r.streams.first().url).host?.take(3)}…")
            val p = runBlocking { EpisodeCache.get(ep).await() }
            log("plugin playable hls=${p.hls} note=${p.note} uri=${p.uri.takeLast(30)}")
            log("PLUGIN RESULT OK")
        } catch (e: Throwable) { log("PLUGIN RESULT FAIL ${e.javaClass.simpleName}: ${e.message}") }
    }
}
