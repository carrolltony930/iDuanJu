package app.iduanju.core

import app.iduanju.core.plugin.PluginSubscription
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSubscriptionTest {
    @Test fun parseResolveRoundTrip() {
        val text = """{"name":"示例订阅","plugins":[
            {"id":"demo","name":"Demo","manifest":"plugins/demo/manifest.json","type":"video","adult":false,"version":"1.2.0"},
            {"id":"bad"},
            {"id":"abs","manifest":"https://cdn.example.org/a.idjplugin"}],
          "cms":[{"name":"站","api":"https://cms.example.org/api.php/provide/vod/"},{"api":"not a url"}]}"""
        val s = PluginSubscription.apply(text, "https://example.org/sub/list.json", now = 1)
        assertEquals("示例订阅", s.name)
        assertEquals(listOf("demo", "abs"), s.plugins.map { it.id })
        assertEquals("https://example.org/sub/plugins/demo/manifest.json", s.plugins[0].manifest)
        assertEquals("https://cdn.example.org/a.idjplugin", s.plugins[1].manifest)
        assertNull(s.plugins[1].adult)
        assertEquals(1, s.cms.size)
        assertEquals(s, PluginSubscription.fromJSON(JSONObject(s.toJSON().toString())))
        assertTrue(PluginSubscription.isSubscription(JSONObject(text)))
        assertEquals("example.org", PluginSubscription.apply("""{"plugins":[]}""", "https://example.org/x.json").name)
    }

    @Test fun bundleFromManifest() {
        val b = JSONObject(PluginSubscription.bundleFromManifest("""{"schema":"idj1","id":"x","entry":"main.js"}""", "export default {}"))
        assertEquals("export default {}", b.getJSONObject("files").getString("main.js"))
        assertEquals("x", b.getJSONObject("manifest").getString("id"))
    }
}
