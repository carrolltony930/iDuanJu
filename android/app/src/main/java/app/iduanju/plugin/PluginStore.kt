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

    /** iOS bundles adult comic plugins under 推荐插件 (never auto-installed). */
    fun bundled(): List<String> = runCatching { ctx.assets.list("plugins")?.filter { it.endsWith(".idjplugin") } ?: emptyList() }.getOrDefault(emptyList())

    companion object {
        const val KEY = "stv.plugins.index"
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
