package app.iduanju.core.plugin

import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A JavaScript engine the runtime can drive (QuickJS on Android; a fake in JVM tests). All calls happen on the
 * instance's single plugin thread. `__hostRaw(name, argsJSON)` must be installed as a global that forwards to [host]
 * and returns its string (JS `undefined` for null).
 */
interface PluginEngine {
    fun install(hostRaw: (String, String) -> String?)
    /** Evaluates a classic script; returns String/Number/Boolean or null. Throws on a JS error. */
    fun eval(src: String, name: String): Any?
    fun close()
}

/** JS glue shared by every engine: error envelope around the iOS prelude, `export default` capture, promise-settling call. */
object PluginGlue {
    const val ERR = "\u0001E"

    /** iOS PluginPrelude.source, fed with a `__host` that turns the host error envelope into a thrown Error with `.code`. */
    val prelude: String = """
globalThis.__host = (function (hr) { return function (n, a) {
  const r = hr(n, a);
  if (typeof r === 'string' && r.charCodeAt(0) === 1) { const e = JSON.parse(r.slice(2)); const err = new Error(e.message); err.code = e.code; throw err; }
  return r === null || r === undefined || r === '' ? undefined : r;
}; })(globalThis.__hostRaw);
""" + PRELUDE + """
(function () {
  delete globalThis.__hostRaw;
  try { delete globalThis.eval; } catch (e) {}
  try { Object.defineProperty(Function.prototype, 'constructor', { value: undefined, writable: false, configurable: false }); } catch (e) {}
  globalThis.__idjCall = function (fn, argJSON) {
    globalThis.__idjState = 'pending'; globalThis.__idjResult = '';
    const done = (st, v) => { globalThis.__idjState = st; globalThis.__idjResult = v; };
    const p = globalThis.__plugin;
    if (!p || typeof p[fn] !== 'function') { done('error', JSON.stringify({ code: 'E_UNSUPPORTED', message: '插件未实现 ' + fn })); return; }
    Promise.resolve().then(() => p[fn](JSON.parse(argJSON))).then(
      (v) => done('ok', JSON.stringify(v === undefined ? null : v)),
      (e) => done('error', JSON.stringify({ code: (e && e.code) || 'E_INTERNAL', message: String((e && e.message) || e) })));
  };
  globalThis.__idjHas = function (fn) { const p = globalThis.__plugin; return !!(p && typeof p[fn] === 'function'); };
})();
"""

    /** ES module `export default {…}` → global (plugins may not import/export anything else; iOS sandbox has no loader either). */
    fun script(source: String): String {
        if (Regex("^\\s*import\\s", RegexOption.MULTILINE).containsMatchIn(source)) throw PluginException("E_INSTALL", "插件不能使用 import")
        val n = Regex("^(\\s*)export\\s+default\\s+", RegexOption.MULTILINE)
        if (!n.containsMatchIn(source)) throw PluginException("E_INSTALL", "入口文件必须 export default 一个对象")
        val s = n.replaceFirst(source, "$1globalThis.__plugin = ")
        if (Regex("^\\s*export\\s", RegexOption.MULTILINE).containsMatchIn(s)) throw PluginException("E_INSTALL", "入口文件只能有一个 export default")
        return "(function(){'use strict';\n$s\n})();"
    }

    const val PRELUDE = """
(function (h) {
  'use strict';
  const call = (n, a) => { const r = h(n, JSON.stringify(a === undefined ? null : a)); return r === undefined ? undefined : JSON.parse(r); };
  const deepFreeze = (o) => { for (const k of Object.getOwnPropertyNames(o)) { const v = o[k]; if (v && typeof v === 'object' && !Object.isFrozen(v)) deepFreeze(v); } return Object.freeze(o); };
  const node = (id) => Object.freeze({
    text: () => call('html.text', { id }),
    html: () => call('html.html', { id }),
    outerHtml: () => call('html.outer', { id }),
    attr: (k) => call('html.attr', { id, k: String(k) }),
    select: (q) => call('html.select', { id, q: String(q) }).map(node),
    selectFirst: (q) => { const r = call('html.select', { id, q: String(q), first: true }); return r.length ? node(r[0]) : null; }
  });
  const opt = (o, extra) => Object.assign({}, o || {}, extra);
  const host = {
    version: call('host.version'),
    has: (cap) => call('host.has', { cap: String(cap) }),
    http: {
      request: async (o) => call('http.request', o || {}),
      get: async (url, o) => call('http.request', opt(o, { url: String(url) })),
      post: async (url, body, o) => call('http.request', opt(o, { url: String(url), method: 'POST', body: body })),
      allowed: (url) => call('http.allowed', { url: String(url) })
    },
    html: { parse: (text, baseUrl) => node(call('html.parse', { text: text == null ? '' : String(text), base: baseUrl ? String(baseUrl) : '' })) },
    url: {
      resolve: (base, rel) => call('url.resolve', { base: String(base), rel: String(rel) }),
      encodeForm: (obj) => Object.keys(obj || {}).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] == null ? '' : String(obj[k]))).join('&')
    },
    crypto: {
      md5: (s, o) => call('crypto.hash', opt(o, { alg: 'md5', data: String(s) })),
      sha1: (s, o) => call('crypto.hash', opt(o, { alg: 'sha1', data: String(s) })),
      sha256: (s, o) => call('crypto.hash', opt(o, { alg: 'sha256', data: String(s) })),
      sha512: (s, o) => call('crypto.hash', opt(o, { alg: 'sha512', data: String(s) })),
      hmac: (alg, key, data, o) => call('crypto.hmac', opt(o, { alg: String(alg), key: String(key), data: String(data) })),
      aes: (o) => call('crypto.aes', o || {}),
      rsa: (o) => call('crypto.rsa', o || {}),
      randomHex: (n) => call('crypto.random', { n: n | 0 })
    },
    codec: {
      base64Encode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: (o && o.from) || 'utf8', to: 'base64' })),
      base64Decode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: 'base64', to: (o && o.to) || 'utf8' })),
      hexEncode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: (o && o.from) || 'utf8', to: 'hex' })),
      convert: (s, from, to) => call('codec.convert', { data: String(s), from: String(from), to: String(to) }),
      gunzip: (b64) => call('codec.gunzip', { data: String(b64) }),
      unpackPacker: (js) => call('codec.unpack', { js: String(js) })
    },
    m3u8: { parse: (text, baseUrl) => call('m3u8.parse', { text: String(text), base: baseUrl ? String(baseUrl) : '' }) },
    storage: {
      get: (k) => call('storage.get', { k: String(k) }),
      set: (k, v) => { call('storage.set', { k: String(k), v: v === undefined ? null : v }); },
      remove: (k) => { call('storage.remove', { k: String(k) }); }
    },
    settings: call('settings.all') || {},
    error: (code, message) => { const e = new Error(String(message == null ? code : message)); e.code = String(code); return e; },
    log: (...a) => { call('log', { m: a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') }); }
  };
  deepFreeze(host);
  Object.defineProperty(globalThis, 'host', { value: host, enumerable: false, writable: false, configurable: false });
  const con = Object.freeze({ log: host.log, info: host.log, warn: host.log, error: host.log });
  Object.defineProperty(globalThis, 'console', { value: con, enumerable: false, writable: false, configurable: false });
})(globalThis.__host);
delete globalThis.__host;
"""
}

/** One loaded plugin: its own thread, engine context and host (iOS PluginInstance). */
class PluginInstance(val bundle: PluginBundle, val host: PluginHost, engineFactory: () -> PluginEngine) {
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-" + bundle.manifest.id).apply { isDaemon = true } }
    private var engine: PluginEngine? = null
    @Volatile var broken: String? = null; private set

    init {
        run(15.0) {
            val e = engineFactory()
            e.install { n, a ->
                try { host.handle(n, a) } catch (x: PluginException) { PluginGlue.ERR + x.json() }
                catch (x: Exception) { PluginGlue.ERR + PluginException("E_INTERNAL", x.message ?: x.toString()).json() }
            }
            e.eval(PluginGlue.prelude, "prelude.js")
            e.eval(PluginGlue.script(bundle.source), bundle.manifest.entry)
            engine = e
        }
    }

    private fun <T> run(timeout: Double, f: () -> T): T {
        broken?.let { throw PluginException("E_INTERNAL", it) }
        val fut = thread.submit(Callable(f))
        return try { fut.get((timeout * 1000).toLong(), TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) {
            // QuickJS here has no interrupt hook: abandon this context (the thread is left to finish on its own).
            broken = "插件执行超时，已停用（重新打开 App 后恢复）"; fut.cancel(true); thread.shutdownNow()
            throw PluginException("E_TIMEOUT", "${timeout.toInt()}s")
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            throw if (c is PluginException) c else PluginException("E_INTERNAL", c?.message ?: c.toString())
        }
    }

    fun has(fn: String): Boolean = run(5.0) { engine?.eval("__idjHas(${JSONObject.quote(fn)})", "has.js") == true }

    /** Calls `default[fn](arg)`; returns the parsed JSON result (JSONObject/JSONArray/primitive/JSONObject.NULL). */
    fun call(fn: String, arg: JSONObject, timeout: Double = 30.0): Any = run(timeout) {
        val e = engine ?: throw PluginException("E_INTERNAL", "插件已停用")
        host.beginCall()
        try {
            e.eval("__idjCall(${JSONObject.quote(fn)}, ${JSONObject.quote(arg.toString())})", "call.js")
            val st = e.eval("globalThis.__idjState", "st.js")?.toString()
            val r = e.eval("globalThis.__idjResult", "r.js")?.toString() ?: ""
            when (st) {
                "ok" -> JSONTokener(r.ifEmpty { "null" }).nextValue()
                "error" -> throw PluginException.fromJSON(r)
                else -> throw PluginException("E_INTERNAL", "插件 Promise 未完成（$fn）")
            }
        } finally { host.endCall() }
    }

    fun close() { try { thread.submit { engine?.close(); engine = null }.get(2, TimeUnit.SECONDS) } catch (_: Exception) {}; thread.shutdownNow() }
}
