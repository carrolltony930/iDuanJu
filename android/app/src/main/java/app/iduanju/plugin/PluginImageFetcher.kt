package app.iduanju.plugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import app.iduanju.IdjApp
import app.iduanju.core.plugin.PluginMedia
import app.iduanju.core.util.NetGuard
import okio.Buffer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Coil fetcher for `idjimg://` plugin image refs (iOS PluginMedia.load): allow-list per hop, allowed headers only, no cookies, ≤30 MB, transforms. */
class PluginImageFetcher(private val ref: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val r = PluginMedia.parse(ref) ?: error("图片地址无效")
        val policy = IdjApp.instance.plugins.mediaPolicy(r.plugin) ?: error("插件未启用")
        fun check(u: okhttp3.HttpUrl) { if ((u.scheme != "https" && u.scheme != "http") || !policy.allows(u.host) || NetGuard.isIPLiteral(u.host) || NetGuard.resolvesToBlocked(u.host)) error("图片域名未授权：${u.host}") }
        var url = r.url.toHttpUrlOrNull() ?: error("图片地址无效")
        var bytes: ByteArray? = null
        for (hop in 0..5) {
            check(url)
            val b = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            r.headers.forEach { (k, v) -> if (k.lowercase() in app.iduanju.core.plugin.PluginOut.allowedHeaders) b.header(k, v) }
            http.newCall(b.build()).execute().use { resp ->
                if (resp.isRedirect) { url = resp.header("Location")?.let { url.resolve(it) } ?: error("跳转地址无效"); return@use }
                if (!resp.isSuccessful) error("图片 HTTP ${resp.code} · ${url.host}")
                val len = resp.body?.contentLength() ?: -1; if (len > 30L shl 20) error("图片太大")
                bytes = resp.body?.bytes()
            }
            if (bytes != null) break
        }
        var data = bytes ?: error("跳转过多")
        r.transform?.let { t ->
            data = when (t.optString("type")) {
                "aes" -> PluginMedia.aes(t, data)
                "stripes" -> unscramble(data, t.optInt("segments"))
                else -> data
            }
        }
        return SourceResult(ImageSource(Buffer().write(data), options.context), null, DataSource.NETWORK)
    }

    private fun unscramble(data: ByteArray, n: Int): ByteArray {
        if (n <= 1) return data
        val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("图片无法解码")
        val plan = PluginMedia.stripes(src.height, n); if (plan.isEmpty()) return data
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for ((sy, dy, h) in plan) c.drawBitmap(src, Rect(0, sy, src.width, sy + h), Rect(0, dy, src.width, dy + h), null)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    class Factory : Fetcher.Factory<android.net.Uri> {
        override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == "idjimg") PluginImageFetcher(data.toString(), options) else null
    }

    companion object {
        private val http = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
}
