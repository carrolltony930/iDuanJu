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
data class Playable(val uri: String, val quality: String?, val codec: String, val encrypted: Boolean)

/**
 * Resolve + (for CENC) download & decrypt to cache. Also used to precache the next episode
 * (iOS preloads the next episode's stream while the current one plays).
 */
object EpisodeCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Playable>>()
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    private fun dir(): File = File(IdjApp.instance.cacheDir, "hg-play").apply { mkdirs() }

    fun get(vid: String, onStatus: (String) -> Unit = {}): Deferred<Playable> =
        inflight[vid]?.takeIf { !it.isCancelled && !(it.isCompleted && it.getCompletionExceptionOrNull() != null) }
            ?: scope.async { load(vid, onStatus) }.also { inflight[vid] = it }

    fun preload(vid: String) { get(vid) }

    private fun load(vid: String, onStatus: (String) -> Unit): Playable {
        val r = IdjApp.instance.hongguo.resolve(vid)
        val s = r.streams.firstOrNull() ?: throw Exception(r.err ?: "无可用流")
        if (s.key.isEmpty()) return Playable(s.url, s.quality, s.codec, false)
        val f = File(dir(), "$vid.mp4")
        if (f.length() > 0) return Playable(f.absolutePath, s.quality, s.codec, true)
        onStatus("下载解密…")
        val enc = http.newCall(Request.Builder().url(s.url).header("User-Agent", "com.phoenix.read/73932").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("媒体 HTTP ${resp.code}")
            resp.body!!.bytes()
        }
        if (enc.isEmpty()) throw Exception("媒体空包")
        val dec = CENCDecryptor.decryptCopy(enc, s.key)
        val tmp = File(dir(), "$vid.part"); tmp.writeBytes(dec); tmp.renameTo(f)
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
