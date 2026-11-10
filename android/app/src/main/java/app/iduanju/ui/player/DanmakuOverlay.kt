package app.iduanju.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** iOS DanmakuSettings defaults: 1/3 screen, 4 rows, medium speed (8.5 s), 16 pt, 0.85 opacity, merge + likes priority. */
object DanmakuPrefs {
    const val AREA = 0.34f; const val ROWS = 4; const val DURATION = 8.5; const val FONT = 16f; const val OPACITY = 0.85f
    val client by lazy {
        HGDanmakuClient {
            val sp = IdjApp.instance.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE)
            val a = sp.getString("stv.danmaku.did", null); val b = sp.getString("stv.danmaku.iid", null)
            if (a?.length == 19 && b?.length == 19) a to b
            else (HGCommentSign.newDeviceId() to HGCommentSign.newDeviceId()).also { sp.edit().putString("stv.danmaku.did", it.first).putString("stv.danmaku.iid", it.second).apply() }
        }
    }
}

/** Scrolling bullet-comment layer synced to the player clock (iOS DanmakuOverlay). Never intercepts touches. */
@Composable
fun DanmakuOverlay(player: Player, seriesId: String, vid: String, laneTop: Dp, bottomLimit: Dp, onStatus: (String) -> Unit) {
    var raw by remember(vid) { mutableStateOf<List<HGDanmaku>>(emptyList()) }
    LaunchedEffect(vid) {
        onStatus("弹幕加载中…")
        val r = withContext(Dispatchers.IO) { DanmakuPrefs.client.fetch(seriesId, vid) }
        raw = r.items
        onStatus(if (r.items.isEmpty()) r.notice else r.notice.ifEmpty { "本集 ${r.items.size} 条" })
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val W = with(density) { maxWidth.toPx() }
        val laneH = DanmakuPrefs.FONT * 1.55f
        val rows = run {
            val usable = (maxHeight - laneTop - bottomLimit).value.coerceAtLeast(0f)
            val h = minOf(usable, maxOf(laneH, (maxHeight - laneTop).value * DanmakuPrefs.AREA))
            (h / laneH).toInt().coerceIn(1, DanmakuPrefs.ROWS)
        }
        // layout in dp units like iOS points
        val placed = remember(raw, maxWidth, rows) {
            DanmakuLayout.layout(DanmakuMerger.merge(raw), rows, maxWidth.value, DanmakuPrefs.FONT, DanmakuPrefs.DURATION, true)
        }
        var t by remember { mutableDoubleStateOf(0.0) }
        LaunchedEffect(placed) {
            while (isActive) { withFrameNanos { }; t = player.currentPosition / 1000.0 }
        }
        if (placed.isEmpty()) return@BoxWithConstraints
        val styleBase = TextStyle(fontSize = DanmakuPrefs.FONT.sp, shadow = Shadow(Color.Black.copy(0.85f), Offset(0f, 1f), 2.4f))
        Canvas(Modifier.fillMaxSize()) {
            val dur = DanmakuPrefs.DURATION
            var lo = 0; var hi = placed.size
            while (lo < hi) { val m = (lo + hi) / 2; if (placed[m].item.time < t - dur) lo = m + 1 else hi = m }
            var i = lo
            while (i < placed.size) {
                val p = placed[i]; if (p.item.time > t) break; i++
                val elapsed = t - p.item.time
                if (elapsed < 0 || elapsed >= dur || p.lane >= rows) continue
                val wPx = p.width.dp.toPx()
                val x = (W - (elapsed / dur).toFloat() * (W + wPx))
                val y = laneTop.toPx() + p.lane * laneH.dp.toPx()
                val merged = p.item.count > 1
                val color = runCatching { Color(android.graphics.Color.parseColor(p.item.color)) }.getOrDefault(Color.White).copy(alpha = DanmakuPrefs.OPACITY)
                val layout = measurer.measure(p.text, styleBase.copy(color = color, fontWeight = if (merged || p.item.likes >= 50) FontWeight.Bold else FontWeight.SemiBold))
                drawText(layout, topLeft = Offset(x, y - layout.size.height / 2f))
                if (merged) {
                    val b = measurer.measure(p.badge, styleBase.copy(color = Color(0xFFFFB347).copy(alpha = DanmakuPrefs.OPACITY), fontSize = (DanmakuPrefs.FONT * 0.8f).sp, fontWeight = FontWeight.Black))
                    drawText(b, topLeft = Offset(x + layout.size.width + 4.dp.toPx(), y - b.size.height / 2f))
                }
            }
        }
    }
}
