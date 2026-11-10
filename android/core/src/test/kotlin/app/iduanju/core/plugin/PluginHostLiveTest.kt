package app.iduanju.core.plugin

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** -PcmsApi=<https url> : real http.request through the plugin host (URL never committed). */
class PluginHostLiveTest {
    @Test fun request() {
        val api = System.getProperty("idj.cmsApi") ?: ""
        assumeTrue(api.startsWith("https://"))
        val host = java.net.URI(api).host
        val m = PluginManifest.decode("""{"schema":1,"id":"com.example.live.test","name":"L","version":"1.0.0","author":{"name":"t"},"type":"video","adult":false,
            "minAppVersion":"1.0","entry":"main.js","permissions":{"http":["$host"]},"capabilities":["detail","play"]}""")
        val h = PluginHost(m, Files.createTempDirectory("plg").toFile(), log = { println("host: $it") })
        val r = JSONObject(h.handle("http.request", JSONObject().put("url", "$api?ac=list").put("responseType", "json").toString())!!)
        println("status=${r.getInt("status")} classes=${r.getJSONObject("body").optJSONArray("class")?.length()}")
    }
}
