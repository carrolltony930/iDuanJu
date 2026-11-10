package app.iduanju.core.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * iOS PluginSubscription: `{ "name", "plugins": [ { "id", "name", "manifest", "type", "adult", "version", "description" } ],
 * "cms": [ { "name", "api", "adult" } ] }`. Manifest URLs resolve against the subscription URL. Nothing is auto-installed.
 */
data class PluginSubscription(
    val url: String,
    val name: String = "",
    val plugins: List<Entry> = emptyList(),
    val cms: List<CMSEntry> = emptyList(),
    val fetchedAt: Long = 0,
    val error: String? = null,
) {
    data class Entry(val id: String, val name: String, val manifest: String, val type: String?, val adult: Boolean?, val version: String?, val description: String?)
    data class CMSEntry(val name: String, val api: String, val adult: Boolean?)

    fun toJSON(): JSONObject = JSONObject().put("url", url).put("name", name).put("fetchedAt", fetchedAt).put("error", error)
        .put("plugins", JSONArray(plugins.map { JSONObject().put("id", it.id).put("name", it.name).put("manifest", it.manifest).put("type", it.type).put("adult", it.adult).put("version", it.version).put("description", it.description) }))
        .put("cms", JSONArray(cms.map { JSONObject().put("name", it.name).put("api", it.api).put("adult", it.adult) }))

    companion object {
        private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
        private fun JSONObject.optBool(k: String): Boolean? = if (has(k) && !isNull(k)) opt(k) as? Boolean else null

        fun isSubscription(o: JSONObject) = o.has("plugins") || o.has("cms")

        /** Parses a fetched list (throws on non-object JSON). */
        fun apply(text: String, url: String, now: Long = System.currentTimeMillis()): PluginSubscription {
            val o = runCatching { JSONObject(text) }.getOrElse { throw PluginException("E_INSTALL", "订阅不是 JSON 对象") }
            val base = java.net.URI(url)
            val host = base.host ?: "订阅"
            val entries = (o.optJSONArray("plugins") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optStr("id") ?: return@mapNotNull null
                val m = e.optStr("manifest") ?: return@mapNotNull null
                val abs = runCatching { base.resolve(m).toString() }.getOrNull() ?: return@mapNotNull null
                Entry(id, e.optStr("name") ?: id, abs, e.optStr("type"), e.optBool("adult"), e.optStr("version"), e.optStr("description"))
            } }
            val cms = (o.optJSONArray("cms") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val api = e.optStr("api") ?: return@mapNotNull null
                val h = runCatching { java.net.URI(api).host }.getOrNull() ?: return@mapNotNull null
                CMSEntry(e.optStr("name") ?: h, api, e.optBool("adult"))
            } }
            return PluginSubscription(url, o.optStr("name") ?: host, entries, cms, now, null)
        }

        fun fromJSON(o: JSONObject): PluginSubscription {
            val ps = o.optJSONArray("plugins") ?: JSONArray(); val cs = o.optJSONArray("cms") ?: JSONArray()
            return PluginSubscription(o.optString("url"), o.optString("name"),
                (0 until ps.length()).map { ps.getJSONObject(it) }.map { Entry(it.optString("id"), it.optString("name"), it.optString("manifest"), it.optStr("type"), it.optBool("adult"), it.optStr("version"), it.optStr("description")) },
                (0 until cs.length()).map { cs.getJSONObject(it) }.map { CMSEntry(it.optString("name"), it.optString("api"), it.optBool("adult")) },
                o.optLong("fetchedAt"), o.optStr("error"))
        }

        /** A bare manifest (`{"schema":"idj1",…}`) + its entry script → the single-file .idjplugin bundle text. */
        fun bundleFromManifest(manifestText: String, js: String): String {
            val m = JSONObject(manifestText)
            return JSONObject().put("manifest", m).put("files", JSONObject().put(m.optString("entry"), js)).toString()
        }
    }
}
