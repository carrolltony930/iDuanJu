package app.iduanju.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.data.WatchProgress
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(seriesId: String, startEp: Int, onBack: () -> Unit) {
    val c = IdjThemeColors
    val ctx = LocalContext.current
    val lib = IdjApp.instance.library
    var detail by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var epIndex by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("加载中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var holdSpeed by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var danmaku by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf<String?>(null) }
    var fillMode by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(TechTags()) }
    var retry by remember { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().also { it.playWhenReady = true }
    }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val n = detail?.episodes?.size ?: 0
                    if (epIndex + 1 < n) epIndex += 1
                }
            }
            override fun onPlayerError(e: PlaybackException) { error = "播放失败：${e.errorCodeName}" }
            override fun onTracksChanged(t: androidx.media3.common.Tracks) {
                tags = TechTags.from(player, quality)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    LaunchedEffect(seriesId) {
        try {
            val d = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.detail(seriesId, web = false) }
            detail = d
            epIndex = d.episodes.indexOfFirst { it.ep == startEp }.coerceAtLeast(0)
        } catch (e: Exception) { error = e.message ?: "详情失败"; status = "" }
    }

    LaunchedEffect(detail, epIndex, retry) {
        val d = detail ?: return@LaunchedEffect
        val ep = d.episodes.getOrNull(epIndex) ?: return@LaunchedEffect
        error = null; status = "取流中…"
        player.stop(); player.clearMediaItems()
        try {
            val p = EpisodeCache.get(ep.vid) { status = it }.await()
            quality = p.quality
            status = ""
            player.setMediaItem(MediaItem.fromUri(p.uri))
            player.setPlaybackSpeed(speed)
            val resume = lib.progress(seriesId)
            if (resume != null && resume.episodeIndex == epIndex && resume.seconds > 3 && resume.fraction < 0.95f) player.seekTo((resume.seconds * 1000).toLong())
            player.prepare(); player.play()
            // precache next episode (iOS preloads the next stream while this one plays)
            d.episodes.getOrNull(epIndex + 1)?.let { EpisodeCache.preload(it.vid) }
        } catch (e: Exception) {
            status = ""; error = e.message ?: "播放失败"
        }
    }

    LaunchedEffect(player) {
        var tick = 0
        while (isActive) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0); buffered = player.bufferedPosition
            playing = player.isPlaying
            if (++tick % 20 == 0 && playing) detail?.let { d ->
                d.episodes.getOrNull(epIndex)?.let { ep ->
                    lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, pos / 1000.0, dur / 1000.0, System.currentTimeMillis()))
                }
            }
            delay(250)
        }
    }
    LaunchedEffect(showChrome, playing) { if (showChrome && playing) { delay(4000); showChrome = false } }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(700); seekHint = null } }
    DisposableEffect(Unit) {
        onDispose {
            detail?.let { d -> d.episodes.getOrNull(epIndex)?.let { ep ->
                lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, pos / 1000.0, dur / 1000.0, System.currentTimeMillis()))
            } }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            update = { it.resizeMode = if (fillMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
        )
        // gesture layer: tap = chrome, double-tap sides = ±10s, long-press = 2x, vertical swipe = prev/next episode,
        // horizontal drag = scrub.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showChrome = !showChrome },
                        onDoubleTap = { o ->
                            val w = size.width
                            when {
                                o.x < w / 3 -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); seekHint = "-10s" }
                                o.x > w * 2 / 3 -> { player.seekTo(player.currentPosition + 10_000); seekHint = "+10s" }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onPress = {
                            val ok = tryAwaitRelease()
                            if (holdSpeed) { holdSpeed = false; player.setPlaybackSpeed(speed) }
                            ok
                        },
                        onLongPress = { holdSpeed = true; player.setPlaybackSpeed(2f) },
                    )
                }
                .pointerInput(detail) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                        val n = detail?.episodes?.size ?: 0
                        if (acc < -160 && epIndex + 1 < n) epIndex += 1
                        else if (acc > 160 && epIndex > 0) epIndex -= 1
                    }) { _, d -> acc += d }
                }
                .pointerInput(Unit) {
                    var start = 0L; var acc = 0f
                    detectHorizontalDragGestures(onDragStart = { start = player.currentPosition; acc = 0f }, onDragEnd = {
                        player.seekTo((start + (acc / size.width * 90_000).toLong()).coerceIn(0, player.duration.coerceAtLeast(0)))
                        seekHint = null
                    }) { _, d ->
                        acc += d
                        val t = (start + (acc / size.width * 90_000).toLong()).coerceIn(0, player.duration.coerceAtLeast(0))
                        seekHint = fmt(t) + " / " + fmt(player.duration.coerceAtLeast(0))
                    }
                },
        )

        if (status.isNotEmpty() && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color.White.copy(0.8f), fontSize = 13.sp)
            }
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White.copy(0.85f), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("重试", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { retry++ }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
        if (holdSpeed) {
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(0.55f))
                .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FastForward, null, tint = Color.White); Spacer(Modifier.width(8.dp))
                Text("2.0×", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        seekHint?.let {
            Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }

        AnimatedVisibility(showChrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top bar: back · PiP · favourite · more
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleBtn(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", onBack)
                        Spacer(Modifier.weight(1f))
                        CircleBtn(Icons.Filled.PictureInPictureAlt, "画中画") {}
                        Spacer(Modifier.width(12.dp))
                        val fav = lib.isFavorite(seriesId)
                        CircleBtn(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏") {
                            detail?.let { lib.toggleFavorite(seriesId, it.title, it.cover) }
                        }
                        Spacer(Modifier.width(12.dp))
                        CircleBtn(Icons.Filled.MoreHoriz, "更多") { fillMode = !fillMode }
                    }
                    Spacer(Modifier.height(10.dp))
                    TechTagRow(tags)
                }
                // centre transport: -10 · play/pause · +10
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Icon(Icons.Filled.Replay10, "后退10秒", tint = Color.White, modifier = Modifier.size(34.dp).clickable { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) })
                    Box(Modifier.size(80.dp).clip(CircleShape).background(Color.Black.copy(0.45f)).clickable { if (player.isPlaying) player.pause() else player.play() },
                        contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                    Icon(Icons.Filled.Forward10, "前进10秒", tint = Color.White, modifier = Modifier.size(34.dp).clickable { player.seekTo(player.currentPosition + 10_000) })
                }
                // bottom: caption, title, progress, toolbar
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                    val d = detail
                    if (d != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.source, color = Color.White.copy(0.85f), fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(0.18f)).padding(horizontal = 8.dp, vertical = 3.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("第${d.episodes.getOrNull(epIndex)?.ep ?: startEp}集", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(" / 共${maxOf(d.totalEps, d.episodes.size)}集", color = Color.White.copy(0.6f), fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(d.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 2)
                    }
                    Spacer(Modifier.height(18.dp))
                    ProgressBar(pos, dur, buffered) { player.seekTo(it) }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(pos), color = Color.White.copy(0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(if (dur > 0) "−" + fmt(dur - pos) else "", color = Color.White.copy(0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ToolIcon(Icons.Filled.Speed, "倍速") { showSpeed = true }
                        BoxedTag(quality ?: tags.res ?: "自动", filled = false)
                        ToolIcon(Icons.Filled.GraphicEq, "音轨") {}
                        ToolIcon(Icons.AutoMirrored.Filled.Chat, "字幕") {}
                        Box(Modifier.clickable { danmaku = !danmaku }) { BoxedTag("弹", filled = danmaku) }
                        ToolIcon(Icons.Filled.GridView, "选集") { showEpisodes = true }
                        ToolIcon(if (fillMode) Icons.Filled.FullscreenExit else Icons.Filled.OpenInFull, "画面") { fillMode = !fillMode }
                    }
                }
            }
        }

        if (showEpisodes) detail?.let { d ->
            ModalBottomSheet(onDismissRequest = { showEpisodes = false }, containerColor = c.surface) {
                Text("选集 · 共${d.episodes.size}集", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                LazyVerticalGrid(GridCells.Fixed(6), Modifier.heightIn(max = 420.dp).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(d.episodes) { i, ep ->
                        val on = i == epIndex
                        Box(Modifier.heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent.copy(0.17f) else c.text.copy(0.07f))
                            .clickable { epIndex = i; showEpisodes = false }, contentAlignment = Alignment.Center) {
                            Text("${ep.ep}", color = if (on) c.accent else c.text, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (showSpeed) {
            ModalBottomSheet(onDismissRequest = { showSpeed = false }, containerColor = c.surface) {
                Text("倍速", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                        val on = s == speed
                        Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent.copy(0.17f) else c.text.copy(0.07f))
                            .clickable { speed = s; player.setPlaybackSpeed(s); showSpeed = false }, contentAlignment = Alignment.Center) {
                            Text("${s}×".replace(".0×", "×"), color = if (on) c.accent else c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

data class TechTags(val res: String? = null, val range: String? = null, val codec: String? = null, val audio: String? = null, val fps: Int? = null) {
    companion object {
        fun from(p: ExoPlayer, quality: String?): TechTags {
            val v = p.videoFormat; val a = p.audioFormat
            val res = quality ?: v?.let { f -> val h = minOf(f.width, f.height); if (h > 0) "${h}P" else null }
            val codec = v?.sampleMimeType?.let { when { it.contains("hevc") -> "HEVC"; it.contains("avc") -> "H.264"; it.contains("av01") -> "AV1"; else -> null } }
            val range = if (v?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || v?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG) "HDR" else "SDR"
            val audio = a?.let { f -> val n = when { f.sampleMimeType?.contains("mp4a") == true -> "AAC"; f.sampleMimeType?.contains("eac3") == true -> "E-AC-3"; else -> "音频" }
                if (f.channelCount > 0) "$n ${f.channelCount}.0" else n }
            val fps = v?.frameRate?.takeIf { it > 0 }?.let { Math.round(it) }
            return TechTags(res, if (v != null) range else null, codec, audio, fps)
        }
    }
}

@Composable
private fun TechTagRow(t: TechTags) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val items = mutableListOf<@Composable () -> Unit>()
        t.res?.let { items += { BoxedTag(it, filled = true) } }
        t.range?.let { items += { Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
        t.codec?.let { items += { BoxedTag(it, filled = false) } }
        t.audio?.let { a -> items += { val p = a.split(" "); Row { Text(p[0], color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold); if (p.size > 1) Text(" " + p[1], color = Color.White.copy(0.7f), fontSize = 12.sp) } } }
        t.fps?.let { f -> items += { Row { Text("FPS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(" $f", color = Color.White.copy(0.7f), fontSize = 12.sp) } } }
        items.forEachIndexed { i, f -> if (i > 0) Spacer(Modifier.width(12.dp)); f() }
    }
}

@Composable
private fun BoxedTag(text: String, filled: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Text(text, color = if (filled) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(shape).then(if (filled) Modifier.background(Color.White) else Modifier.border(1.dp, Color.White.copy(0.85f), shape))
            .padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun CircleBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** iOS PlayerProgressBar: 3dp track, buffered 40% white, 4x16 thumb; tap/drag to seek. */
@Composable
private fun ProgressBar(pos: Long, dur: Long, buffered: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
    val buf = if (dur > 0) buffered.toFloat() / dur else 0f
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)
        .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width * dur).toLong()) } }
        .pointerInput(dur) {
            detectHorizontalDragGestures(onDragEnd = { drag?.let { if (dur > 0) onSeek((it * dur).toLong()) }; drag = null }) { ch, _ ->
                drag = (ch.position.x / size.width).coerceIn(0f, 1f)
            }
        }, contentAlignment = Alignment.CenterStart) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.25f)))
        Box(Modifier.width(w * buf.coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.40f)))
        Box(Modifier.width(w * frac.coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White))
        val thumb = if (drag != null) 20.dp else 4.dp
        Box(Modifier.offset(x = (w * frac.coerceIn(0f, 1f)) - thumb / 2).size(thumb, if (drag != null) 20.dp else 16.dp)
            .clip(if (drag != null) CircleShape else RoundedCornerShape(2.dp)).background(Color.White))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt(); val h = s / 3600; val m = s / 60 % 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
