package app.iduanju.data

import android.content.Context

/** Public hygiene: optional player device override "did,iid" — never hard-coded. */
class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)

    fun playerDeviceOverride(): Pair<String, String>? {
        val s = sp.getString("hg.purePlayerDevice", null) ?: return null
        val p = s.split(",", limit = 2).map { it.trim() }
        return if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
    }

    fun setPlayerDeviceOverride(did: String?, iid: String?) {
        if (did.isNullOrBlank() || iid.isNullOrBlank()) sp.edit().remove("hg.purePlayerDevice").apply()
        else sp.edit().putString("hg.purePlayerDevice", "$did,$iid").apply()
    }

    /** Developer option: let the player/media download follow the system proxy (default off = iOS parity). */
    var playerUsesSystemProxy: Boolean
        get() = sp.getBoolean("dev.playerSystemProxy", false)
        set(v) { sp.edit().putBoolean("dev.playerSystemProxy", v).apply(); app.iduanju.core.hongguo.HGNet.playerUsesSystemProxy = v }

    var adStripEnabled: Boolean
        get() = sp.getBoolean("adStrip", true)
        set(v) { sp.edit().putBoolean("adStrip", v).apply() }

    var danmakuArea: Float
        get() = sp.getFloat("danmakuArea", 1f / 3f)
        set(v) { sp.edit().putFloat("danmakuArea", v).apply() }
}
