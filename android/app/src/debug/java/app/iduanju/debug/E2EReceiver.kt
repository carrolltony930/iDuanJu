package app.iduanju.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.ui.player.EpisodeCache
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug-only in-app 红果 pipeline probe, logged under tag IDJ-E2E:
 * device_register → rank → detail → resolve (pure/batch/single) → media download → CENC decrypt → MP4 box check.
 * Extras: hgDevice="did,iid" stores the player-device override in settings (same as 「我的」 field; never in source);
 * pluginSelfTest=1 runs the offline QuickJS/idj1 host self-test; plugin=<.idjplugin path on device> installs it and runs home→detail→play.
 * clear=1 removes it; proxy=1|0 lets the player follow the system proxy (emulator egress); sid=<seriesId> to pick a series.
 */
class E2EReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = IdjApp.instance
        intent.getStringExtra("hgDevice")?.split(",")?.takeIf { it.size == 2 }?.let { app.settings.setPlayerDeviceOverride(it[0].trim(), it[1].trim()) }
        intent.getStringExtra("proxy")?.let { app.settings.playerUsesSystemProxy = it == "1" }
        if (intent.getStringExtra("clear") == "1") app.settings.setPlayerDeviceOverride(null, null)
        val sidArg = intent.getStringExtra("sid")
        intent.getStringExtra("cmsApi")?.let { api ->
            // add a 苹果 CMS site at runtime (URL is never in source); logs IDJ-E2E CMS …
            Thread { try { val s = app.cms.add(intent.getStringExtra("cmsName") ?: "", api, false); Log.i("IDJ-E2E", "CMS added ${s.name} cats=${s.categories.size}") }
                     catch (e: Throwable) { Log.i("IDJ-E2E", "CMS add FAIL ${e.message}") } }.start()
            return
        }
        intent.getStringExtra("jsEval")?.let { src -> Thread { File(src).readLines().filter { it.isNotBlank() }.forEach { PluginSelfTest.eval(it) } }.start(); return }
        if (intent.getStringExtra("pluginSelfTest") == "1") { Thread { PluginSelfTest.run() }.start(); return }
        intent.getStringExtra("plugin")?.let { path -> Thread { PluginSelfTest.live(path, intent.getStringExtra("q")) }.start(); return }
        // comicSelfTest=<bundled .idjplugin file>: install it, then list → detail → pages → decode page 1 via Coil (idjimg://). Counts only, no titles.
        intent.getStringExtra("comicSelfTest")?.let { file -> Thread {
            fun log(m: String) = Log.i("IDJ-E2E", m)
            try {
                val rec = app.plugins.install(app.plugins.bundledText(file), "内置")
                val pc = app.plugins.comic(rec.id)
                val cat = pc.categories.first()
                val list = pc.list(cat, 1); log("comic ${rec.id} list items=${list.items.size} hasMore=${list.hasMore}")
                val d = pc.detail(list.items.first()); log("comic detail chapters=${d.chapters.size}")
                val pages = pc.pages(d.item, d.chapters.first()); log("comic pages=${pages.size} scheme=${pages.firstOrNull()?.substringBefore(':')}")
                val r = runBlocking { coil.Coil.imageLoader(ctx).execute(coil.request.ImageRequest.Builder(ctx).data(pages.first()).allowHardware(false).build()) }
                val dr = (r as? coil.request.SuccessResult)?.drawable
                log(if (dr != null) "RESULT OK comic page1 ${dr.intrinsicWidth}x${dr.intrinsicHeight}" else "RESULT FAIL image ${(r as? coil.request.ErrorResult)?.throwable}")
            } catch (e: Throwable) { log("RESULT FAIL ${e.javaClass.simpleName}: ${e.message}") }
        }.start(); return }
        intent.getStringExtra("tgAdd")?.let { Log.i("IDJ-E2E", "TG added ${app.tg.add(it)?.username}"); return }
        Thread {
            fun log(s: String) = Log.i("IDJ-E2E", s)
            try {
                val pd = HGPlayerDevice.current()
                log("player device override=${app.settings.playerDeviceOverride() != null} did=${pd.deviceId.take(4)}…")
                val t0 = System.currentTimeMillis()
                val d = app.hongguo.ensureDevice(); log("device_register ok did=${d.deviceId.take(4)}… (${System.currentTimeMillis() - t0}ms)")
                val rank = app.hongguo.rank(HGRankSub.hot, HGRankGenre.all)
                log("rank hot items=${rank.items.size} first=${rank.items.firstOrNull()?.title}")
                val sid = sidArg ?: rank.items.first().seriesId
                val det = app.hongguo.detail(sid, web = false)
                log("detail sid=$sid title=${det.title} episodes=${det.episodes.size}")
                val ep = det.episodes.first()
                val t1 = System.currentTimeMillis()
                val r = app.hongguo.resolveFresh(ep.vid)
                log("resolve vid=${ep.vid} streams=${r.streams.size} err=${r.err} (${System.currentTimeMillis() - t1}ms) first=${r.streams.firstOrNull()?.let { "${it.quality} ${it.codec} enc=${it.key.isNotEmpty()}" }}")
                if (r.streams.isEmpty()) { log("RESULT FAIL resolve"); return@Thread }
                val p = runBlocking { EpisodeCache.get(ep.vid).await() }
                val f = File(p.uri)
                val head = if (f.exists()) f.inputStream().use { s -> ByteArray(12).also { s.read(it) } } else ByteArray(0)
                val box = if (head.size >= 8) String(head, 4, 4) else "?"
                log("playable uri=${p.uri.take(60)} size=${if (f.exists()) f.length() else -1} firstBox=$box encrypted=${p.encrypted}")
                log("RESULT OK")
            } catch (e: Throwable) {
                log("RESULT FAIL ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }
}
