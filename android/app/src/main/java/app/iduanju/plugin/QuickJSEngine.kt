package app.iduanju.plugin

import app.iduanju.core.plugin.PluginEngine
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext

/** QuickJS (wang.harlon.quickjs wrapper-android) behind the idj1 [PluginEngine] contract. Thread-confined to the plugin thread. */
class QuickJSEngine : PluginEngine {
    private val ctx: QuickJSContext

    init {
        synchronized(QuickJSEngine::class.java) { if (!loaded) { QuickJSLoader.init(); loaded = true } }
        ctx = QuickJSContext.create()
        ctx.setMemoryLimit(64 * 1024 * 1024)
        ctx.setMaxStackSize(1024 * 1024)
    }

    override fun install(hostRaw: (String, String) -> String?) {
        val g = ctx.globalObject
        g.setProperty("__hostRaw", JSCallFunction { args -> hostRaw(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1)?.toString() ?: "null") })
    }

    override fun eval(src: String, name: String): Any? {
        val r = ctx.evaluate(src, name)
        return if (r is JSObject) { val s = r.toString(); r.release(); s } else r
    }

    override fun close() { runCatching { ctx.destroy() } }

    companion object { @Volatile private var loaded = false }
}
