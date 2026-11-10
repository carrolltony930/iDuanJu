package app.iduanju.plugin

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import app.iduanju.core.plugin.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Installed idj1 plugins (iOS PluginStore): files/plugins/<id>/{bundle.idjplugin,storage.json}; index in prefs. */
class PluginStore(private val ctx: Context) {
    data class Installed(val id: String, val name: String, val type: String, val adult: Boolean, val version: String, val enabled: Boolean, val origin: String) {
        fun toJSON() = JSONObject().put("id", id).put("name", name).put("type", type).put("adult", adult).put("version", version).put("enabled", enabled).put("origin", origin)
        companion object { fun of(o: JSONObject) = Installed(o.optString("id"), o.optString("name"), o.optString("type"), o.optBoolean("adult"), o.optString("version"), o.optBoolean("enabled", true), o.optString("origin")) }
    }

    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    private val root = File(ctx.filesDir, "plugins")
    val plugins = mutableStateListOf<Installed>()
    private val live = HashMap<String, PluginVideo>()
    val logs = HashMap<String, ArrayDeque<String>>()

    init { try { val a = JSONArray(sp.getString(KEY, "[]")); for (i in 0 until a.length()) plugins.add(Installed.of(a.getJSONObject(i))) } catch (_: Exception) {} }
    private fun save() { sp.edit().putString(KEY, JSONArray(plugins.map { it.toJSON() }).toString()).apply() }

    fun log(id: String, m: String) {
        android.util.Log.i("idj-plugin", "[$id] $m")
        synchronized(logs) { val q = logs.getOrPut(id) { ArrayDeque() }; q.addLast(m); while (q.size > 200) q.removeFirst() }
    }

    /** Validates + test-loads in a fresh engine before saving (a broken plugin never gets installed). */
    fun install(text: String, origin: String = "manual"): Installed {
        val b = PluginBundle.parse(text)
        val dir = File(root, b.manifest.id)
        val probe = PluginInstance(b, PluginHost(b.manifest, dir, log = { log(b.manifest.id, it) }), ::QuickJSEngine)
        probe.close()
        dir.mkdirs(); File(dir, "bundle.idjplugin").writeText(text)
        synchronized(live) { live.remove(b.manifest.id)?.inst?.close() }
        val m = b.manifest
        val rec = Installed(m.id, m.name, m.typeRaw, m.adult, m.version, plugins.firstOrNull { it.id == m.id }?.enabled ?: true, origin)
        val i = plugins.indexOfFirst { it.id == m.id }
        if (i >= 0) plugins[i] = rec else plugins.add(rec)
        save(); log(m.id, "✓ 已安装 ${m.name} ${m.version}")
        return rec
    }

    fun remove(id: String) {
        synchronized(live) { live.remove(id)?.inst?.close() }
        File(root, id).deleteRecursively(); plugins.removeAll { it.id == id }; save()
    }

    fun setEnabled(id: String, on: Boolean) { val i = plugins.indexOfFirst { it.id == id }; if (i >= 0) { plugins[i] = plugins[i].copy(enabled = on); save() } }

    fun visible(adultUnlocked: Boolean) = plugins.filter { it.enabled && (!it.adult || adultUnlocked) }

    /** Loaded video/shortdrama provider (lazily creates the QuickJS instance on the caller's IO thread). */
    fun video(id: String): PluginVideo = synchronized(live) {
        live[id]?.takeIf { it.inst.broken == null }?.let { return it }
        val text = File(root, "$id/bundle.idjplugin").takeIf { it.exists() }?.readText() ?: throw PluginException("E_INTERNAL", "插件未安装")
        val b = PluginBundle.parse(text)
        val v = PluginVideo(PluginInstance(b, PluginHost(b.manifest, File(root, id), log = { log(id, it) }), ::QuickJSEngine))
        live[id] = v; v
    }

    /** Comic plugin as a manga provider (shares the QuickJS instance with [video]). */
    fun comic(id: String): app.iduanju.core.plugin.PluginComic {
        val v = video(id); val m = v.inst.bundle.manifest
        return app.iduanju.core.plugin.PluginComic(v.inst, m.name, m.description ?: "JS 插件")
    }
    /** Media allow-list for idjimg:// refs (manifest media + http domains); null when the plugin is missing or disabled. */
    fun mediaPolicy(id: String): app.iduanju.core.plugin.DomainPolicy? {
        if (plugins.none { it.id == id && it.enabled }) return null
        return runCatching { video(id).inst.bundle.manifest }.getOrNull()?.let { app.iduanju.core.plugin.DomainPolicy(it.mediaDomains + it.httpDomains) }
    }

    // MARK: subscriptions (iOS PluginStore.subscriptions, key stv.plugins.subscriptions)
    val subscriptions = mutableStateListOf<PluginSubscription>().also { l ->
        runCatching { val a = JSONArray(sp.getString(SUBS, "[]")); for (i in 0 until a.length()) l.add(PluginSubscription.fromJSON(a.getJSONObject(i))) }
    }
    private fun saveSubs() { sp.edit().putString(SUBS, JSONArray(subscriptions.map { it.toJSON() }).toString()).apply() }
    fun addSubscription(url: String): PluginSubscription {
        val u = url.trim()
        val sub = PluginSubscription.apply(download(u), u)
        subscriptions.removeAll { it.url == u }; subscriptions.add(sub); saveSubs(); return sub
    }
    fun refreshSubscriptions() {
        for (i in subscriptions.indices) {
            val s = subscriptions[i]
            subscriptions[i] = runCatching { PluginSubscription.apply(download(s.url), s.url) }.getOrElse { s.copy(error = (it as? PluginException)?.friendly ?: it.message) }
        }
        saveSubs()
    }
    fun removeSubscription(url: String) { subscriptions.removeAll { it.url == url }; saveSubs() }

    sealed class Pasted { data class Plugin(val rec: Installed) : Pasted(); data class Subscription(val sub: PluginSubscription) : Pasted() }
    /** iOS PluginInstaller.interpret: an https manifest / .idjplugin / subscription URL, or a pasted bundle. Blocking. */
    fun interpret(text: String, origin: String? = null): Pasted {
        val t = text.trim()
        if (!t.startsWith("https://") && !t.startsWith("http://")) return Pasted.Plugin(install(t, origin ?: "paste"))
        val body = download(t)
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw PluginException("E_INSTALL", "这个链接不是插件 manifest / .idjplugin / 订阅列表")
        return when {
            PluginSubscription.isSubscription(o) -> Pasted.Subscription(addSubscription(t))
            o.has("manifest") -> Pasted.Plugin(install(body, origin ?: t))
            o.has("schema") -> {
                val entry = o.optString("entry")
                if (!Regex("^[A-Za-z0-9_-]+\\.js$").matches(entry)) throw PluginException("E_INSTALL", "entry 地址无效")
                val js = download(java.net.URI(t).resolve(entry).toString())
                Pasted.Plugin(install(PluginSubscription.bundleFromManifest(body, js), origin ?: t))
            }
            else -> throw PluginException("E_INSTALL", "这个链接不是插件 manifest / .idjplugin / 订阅列表")
        }
    }

    /** iOS bundles adult comic plugins under 推荐插件 (never auto-installed). */
    fun bundled(): List<String> = runCatching { ctx.assets.list("plugins")?.filter { it.endsWith(".idjplugin") }?.sorted() ?: emptyList() }.getOrDefault(emptyList())
    fun bundledText(name: String): String = ctx.assets.open("plugins/$name").use { it.readBytes().toString(Charsets.UTF_8) }
    /** (file, manifest) for 推荐插件; unparsable files are skipped. */
    fun bundledManifests(): List<Pair<String, PluginManifest>> = bundled().mapNotNull { f -> runCatching { f to PluginBundle.parse(bundledText(f)).manifest }.getOrNull() }

    companion object {
        const val KEY = "stv.plugins.index"
        const val SUBS = "stv.plugins.subscriptions"
        private val http = okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()

        /** https only, never to a private/loopback address, ≤2 MB. */
        fun download(url: String): String {
            val u = java.net.URI(url)
            if (u.scheme != "https" || u.host == null) throw PluginException("E_INSTALL", "只支持 https 链接")
            if (app.iduanju.core.util.NetGuard.isIPLiteral(u.host) || app.iduanju.core.util.NetGuard.resolvesToBlocked(u.host)) throw PluginException("E_INSTALL", "地址指向本机/内网，已拦截")
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw PluginException("E_INSTALL", "下载失败 HTTP ${r.code}")
                val b = r.body?.byteStream()?.use { PluginHost.readCapped(it, (2 shl 20) + 1) } ?: ByteArray(0)
                if (b.size > 2 shl 20) throw PluginException("E_INSTALL", "插件文件超过 2MB")
                return String(b, Charsets.UTF_8)
            }
        }
    }
}
