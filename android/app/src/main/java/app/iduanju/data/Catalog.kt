package app.iduanju.data

import app.iduanju.IdjApp
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGResolvedVideo
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.plugin.PluginException
import app.iduanju.core.plugin.PluginVideo
import app.iduanju.core.util.ProviderException
import app.iduanju.core.tg.TGVideo

/** Routes a namespaced series id to its provider (红果 by default, "cms:<key>:<id>" → MacCMS, "plugin:<id>:…" → idj1 plugin). */
object Catalog {
    fun isCMS(sid: String) = sid.startsWith("cms:")

    private fun cmsSite(sid: String) = MacCMS.parseSid(sid)?.first?.let { IdjApp.instance.cms.site(it) }
        ?: throw ProviderException.invalid("采集站已删除")

    private fun plugin(sid: String): PluginVideo = PluginVideo.pluginId(sid)?.let { IdjApp.instance.plugins.video(it) }
        ?: throw ProviderException.invalid("插件已删除")

    private inline fun <T> friendly(f: () -> T): T = try { f() } catch (e: PluginException) { throw ProviderException(e.friendly) }

    fun detail(sid: String, web: Boolean = true): HGSeriesDetail = when {
        isCMS(sid) -> MacCMS.detail(cmsSite(sid), sid)
        PluginVideo.isPlugin(sid) -> friendly { plugin(sid).detail(sid) }
        TGVideo.isTG(sid) -> IdjApp.instance.tg.let { TGVideo.detail(it.client, sid, it.channel(sid.removePrefix("tg:").substringBefore(':'))) }
        else -> IdjApp.instance.hongguo.detail(sid, web)
    }

    fun resolve(ep: HGEpisode): HGResolvedVideo = when {
        isCMS(ep.vid) -> MacCMS.resolve(cmsSite(ep.vid), ep)
        PluginVideo.isPlugin(ep.vid) -> friendly { plugin(ep.vid).resolve(ep) }
        TGVideo.isTG(ep.vid) -> TGVideo.resolve(IdjApp.instance.tg.client, ep)
        else -> IdjApp.instance.hongguo.resolve(ep.vid)
    }
}
