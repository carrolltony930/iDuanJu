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
 * clear=1 removes it; proxy=1|0 lets the player follow the system proxy (emulator egress); sid=<seriesId> to pick a series.
 */
class E2EReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = IdjApp.instance
        intent.getStringExtra("hgDevice")?.split(",")?.takeIf { it.size == 2 }?.let { app.settings.setPlayerDeviceOverride(it[0].trim(), it[1].trim()) }
        intent.getStringExtra("proxy")?.let { app.settings.playerUsesSystemProxy = it == "1" }
        if (intent.getStringExtra("clear") == "1") app.settings.setPlayerDeviceOverride(null, null)
        val sidArg = intent.getStringExtra("sid")
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
