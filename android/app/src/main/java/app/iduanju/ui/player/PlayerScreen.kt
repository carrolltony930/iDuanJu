package app.iduanju.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.iduanju.IdjApp
import app.iduanju.core.cenc.CENCDecryptor
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

@Composable
fun PlayerScreen(seriesId: String, startEp: Int, onBack: () -> Unit) {
    val c = IdjThemeColors
    val ctx = LocalContext.current
    var detail by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var epIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("加载中…") }
    var showChrome by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tech by remember { mutableStateOf("") }

    val player = remember {
        ExoPlayer.Builder(ctx).build().also {
            it.playWhenReady = true
            it.repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(seriesId) {
        try {
            detail = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.detail(seriesId, web = false) }
            val eps = detail!!.episodes
            epIndex = eps.indexOfFirst { it.ep == startEp }.coerceAtLeast(0)
        } catch (e: Exception) { status = e.message ?: "详情失败" }
    }

    LaunchedEffect(detail, epIndex) {
        val d = detail ?: return@LaunchedEffect
        val ep = d.episodes.getOrNull(epIndex) ?: return@LaunchedEffect
        status = "取流中…"
        player.stop(); player.clearMediaItems()
        try {
            val path = withContext(Dispatchers.IO) {
                val r = IdjApp.instance.hongguo.resolve(ep.vid)
                val s = r.streams.firstOrNull() ?: throw Exception(r.err ?: "无流")
                tech = listOfNotNull(s.quality, s.codec.uppercase().ifEmpty { null }, if (s.key.isNotEmpty()) "CENC" else null).joinToString(" · ")
                if (s.key.isNotEmpty()) {
                    status = "下载解密…"
                    val conn = URI(s.url).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 20000; conn.readTimeout = 120000
                    conn.setRequestProperty("User-Agent", "com.phoenix.read/73932")
                    val enc = conn.inputStream.use { it.readBytes() }
                    val dec = CENCDecryptor.decryptCopy(enc, s.key)
                    val f = File(ctx.cacheDir, "hg-${ep.vid}.mp4")
                    f.writeBytes(dec)
                    f.absolutePath
                } else s.url
            }
            status = ""
            player.setMediaItem(MediaItem.fromUri(path))
            player.prepare()
            player.play()
        } catch (e: Exception) {
            status = e.message ?: "播放失败"
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onTap = { showChrome = !showChrome })
        },
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (status.isNotEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = c.accent)
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color.White.copy(0.8f), fontSize = 13.sp)
            }
        }

        if (showChrome) {
            // top
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                if (tech.isNotEmpty()) {
                    Text(tech, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.15f)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            // center play
            Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(0.35f))
                .clickable { if (player.isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            // bottom
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                val d = detail
                if (d != null) {
                    Text("红果短剧", color = Color.White.copy(0.6f), fontSize = 11.sp)
                    Text("第 ${d.episodes.getOrNull(epIndex)?.ep ?: startEp} 集 / 共 ${d.totalEps} 集", color = Color.White.copy(0.8f), fontSize = 12.sp)
                    Text(d.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                // thin progress
                val p = if (dur > 0) (pos.toFloat() / dur) else 0f
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(c.accent))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(pos), color = Color.White.copy(0.7f), fontSize = 11.sp)
                    Text(if (dur > 0) "-${fmt(dur - pos)}" else "", color = Color.White.copy(0.7f), fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("倍速", "清晰度", "音轨", "弹", "选集").forEach { label ->
                        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.clickable {
                            if (label == "选集") {
                                val next = (epIndex + 1) % (detail?.episodes?.size ?: 1)
                                epIndex = next
                            }
                        }.padding(8.dp))
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt(); val m = s / 60; val r = s % 60
    return "%d:%02d".format(m, r)
}
