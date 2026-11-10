package app.iduanju.ui.player

import app.iduanju.IdjApp
import app.iduanju.core.cenc.CENCDecryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A ready-to-play episode: local decrypted file (CENC) or a direct URL. */
data class Playable(val uri: String, val quality: String?, val codec: String, val encrypted: Boolean, val hls: Boolean = false, val headers: Map<String, String> = emptyMap(), val note: String? = null)

/**
 * Resolve + (for CENC) download & decrypt to cache. Also used to precache the next episode
 * (iOS preloads the next episode's stream while the current one plays).
 */
object EpisodeCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Playable>>()
    private val httpDirect = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val httpSystem = httpDirect.newBuilder().proxy(null).build()
    private val http: OkHttpClient get() = if (app.iduanju.core.hongguo.HGNet.playerUsesSystemProxy) httpSystem else httpDirect

    private fun dir(): File = File(IdjApp.instance.cacheDir, "hg-play").apply { mkdirs() }

    fun get(vid: String, onStatus: (String) -> Unit = {}): Deferred<Playable> = get(app.iduanju.core.hongguo.HGEpisode(0, vid), onStatus)

    fun get(ep: app.iduanju.core.hongguo.HGEpisode, onStatus: (String) -> Unit = {}): Deferred<Playable> =
        inflight[ep.vid]?.takeIf { !it.isCancelled && !(it.isCompleted && it.getCompletionExceptionOrNull() != null) }
            ?: scope.async { load(ep, onStatus) }.also { inflight[ep.vid] = it }

    fun preload(ep: app.iduanju.core.hongguo.HGEpisode) { get(ep) }

    private fun safeName(vid: String) = vid.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun load(ep: app.iduanju.core.hongguo.HGEpisode, onStatus: (String) -> Unit): Playable {
        val vid = ep.vid
        val r = app.iduanju.data.Catalog.resolve(ep)
        val s = DecoderCaps.pick(r.streams) ?: throw Exception(r.err ?: "无可用流")
        if (s.kind == app.iduanju.core.hongguo.HGStreamKind.hls) {
            // iOS serves the ad-stripped playlist from its loopback proxy; here it is written to a local file.
            onStatus("解析播放列表…")
            val prep = app.iduanju.core.hls.HLSLocal.prepare(s.url) { u ->
                http.newCall(Request.Builder().url(u).apply { s.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("播放列表 HTTP ${resp.code}")
                    resp.body!!.string()
                }
            }
            val f = File(dir(), safeName(vid) + ".m3u8"); f.writeText(prep.text)
            val note = if (prep.removed > 0) "已去广告 ${prep.removed} 段 · ${prep.removedSeconds.toInt()}s" else null
            return Playable(f.absolutePath, s.quality, s.codec, false, hls = true, headers = s.headers, note = note)
        }
        if (s.key.isEmpty()) return Playable(s.url, s.quality, s.codec, false, headers = s.headers)
        val f = File(dir(), "${safeName(vid)}.${s.quality ?: "q"}.${s.codec}.mp4")
        if (f.length() > 0) return Playable(f.absolutePath, s.quality, s.codec, true)
        onStatus("下载解密…")
        val enc = http.newCall(Request.Builder().url(s.url).header("User-Agent", "com.phoenix.read/73932").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("媒体 HTTP ${resp.code}")
            resp.body!!.bytes()
        }
        if (enc.isEmpty()) throw Exception("媒体空包")
        val dec = CENCDecryptor.decryptCopy(enc, s.key)
        val tmp = File(dir(), f.name + ".part"); tmp.writeBytes(dec); tmp.renameTo(f)
        trim()
        return Playable(f.absolutePath, s.quality, s.codec, true)
    }

    /** Keep the cache under ~600 MB (oldest first). */
    private fun trim() {
        val files = dir().listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 600L shl 20) break; total -= f.length(); f.delete() }
    }
}
