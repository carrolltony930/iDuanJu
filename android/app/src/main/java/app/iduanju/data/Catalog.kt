package app.iduanju.data

import app.iduanju.IdjApp
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGResolvedVideo
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.util.ProviderException

/** Routes a namespaced series id to its provider (红果 by default, "cms:<key>:<id>" → MacCMS). */
object Catalog {
    fun isCMS(sid: String) = sid.startsWith("cms:")

    private fun cmsSite(sid: String) = MacCMS.parseSid(sid)?.first?.let { IdjApp.instance.cms.site(it) }
        ?: throw ProviderException.invalid("采集站已删除")

    fun detail(sid: String, web: Boolean = true): HGSeriesDetail =
        if (isCMS(sid)) MacCMS.detail(cmsSite(sid), sid) else IdjApp.instance.hongguo.detail(sid, web)

    fun resolve(ep: HGEpisode): HGResolvedVideo =
        if (isCMS(ep.vid)) MacCMS.resolve(cmsSite(ep.vid), ep) else IdjApp.instance.hongguo.resolve(ep.vid)
}
