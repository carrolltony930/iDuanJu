package app.iduanju.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.ui.home.HomeFmt
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS EpisodeGroups: 30 (≤300), 50 (≤600), 60; ≤60 one group. */
object EpisodeGroups {
    fun size(total: Int) = when { total <= 60 -> maxOf(total, 1); total <= 300 -> 30; total <= 600 -> 50; else -> 60 }
    fun ranges(total: Int): List<IntRange> {
        if (total <= 0) return emptyList()
        val g = size(total)
        if (total <= g) return listOf(0 until total)
        return (0 until total step g).map { it..minOf(it + g - 1, total - 1) }
    }
    fun label(r: IntRange, eps: List<HGEpisode>): String {
        val a = eps.getOrNull(r.first)?.ep ?: (r.first + 1); val b = eps.getOrNull(r.last)?.ep ?: (r.last + 1)
        return if (a == b) "$a" else "$a-$b"
    }
}

@Composable
fun DetailScreen(seriesId: String, onBack: () -> Unit, onPlay: (String, Int) -> Unit, onOpenSeries: (String) -> Unit = {}) {
    val c = IdjThemeColors
    val lib = IdjApp.instance.library
    var d by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(seriesId, reload) {
        err = null
        try { d = withContext(Dispatchers.IO) { app.iduanju.data.Catalog.detail(seriesId) } }
        catch (e: Exception) { err = e.message ?: "加载失败" }
    }
    val resume = lib.progress(seriesId)
    var group by remember(d) {
        mutableIntStateOf(d?.let { dd -> EpisodeGroups.ranges(dd.episodes.size).indexOfFirst { resume != null && resume.episodeIndex in it }.coerceAtLeast(0) } ?: 0)
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // hero: 440 poster, scrim, centred white title + meta
            Box(Modifier.fillMaxWidth().height(440.dp), contentAlignment = Alignment.BottomCenter) {
                AsyncImage(d?.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    // iOS DramaDetailView: dark scrim behind the white title / meta, then a short fade into the page colour
                    0f to Color.Black.copy(0.25f), 0.3f to Color.Transparent, 0.9f to Color.Black.copy(0.62f), 1f to c.bg)))
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d?.title ?: "", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 41.sp)
                    d?.let { dd ->
                        Text(
                            listOfNotNull(dd.source, dd.episodeLabel.ifEmpty { "共${maxOf(dd.totalEps, dd.episodes.size)}集" },
                                if (dd.favoriteCount > 0) HomeFmt.count(dd.favoriteCount) + "收藏" else null, dd.author.ifEmpty { null })
                                .joinToString(" · "),
                            color = Color.White.copy(0.75f), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                        )
                    }
                }
            }
            val dd = d
            when {
                dd != null -> DetailBody(dd, seriesId, resume, group, { group = it }, onPlay, onOpenSeries)
                err != null -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err!!, color = c.secondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("重试", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { reload++ }.padding(8.dp))
                }
                else -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(c.elevated.copy(0.6f))) }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
        Box(Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(0.28f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun DetailBody(
    d: HGSeriesDetail, sid: String, resume: app.iduanju.data.WatchProgress?, group: Int, setGroup: (Int) -> Unit,
    onPlay: (String, Int) -> Unit, onOpenSeries: (String) -> Unit,
) {
    val c = IdjThemeColors
    val lib = IdjApp.instance.library
    val glass = c.text.copy(0.07f)
    val ranges = EpisodeGroups.ranges(d.episodes.size)
    val g = if (group in ranges.indices) group else 0
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val startEp = resume?.episodeNumber ?: d.episodes.firstOrNull()?.ep ?: 1
        Row(
            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).background(c.text)
                .clickable(enabled = d.episodes.isNotEmpty()) { onPlay(sid, startEp) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = c.bg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (resume != null) "继续第${resume.episodeNumber}集" else "开始播放", color = c.bg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        val fav = lib.isFavorite(sid)
        Box(Modifier.size(50.dp).clip(CircleShape).background(c.elevated).clickable { lib.toggleFavorite(sid, d.title, d.cover) }, contentAlignment = Alignment.Center) {
            Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", tint = if (fav) c.accent else c.text)
        }
    }
    if (d.tags.isNotEmpty()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(d.tags) { t ->
                Text(t, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
    if (d.cast.isEmpty() && d.actors.isNotEmpty()) {
        Text("主演：" + d.actors.joinToString(" / "), color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (d.desc.isNotEmpty()) {
        Text(d.desc, color = c.secondary, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (d.relatedSeries.size > 1) RelatedShelf(d.relatedSeries, d.seriesId, onOpenSeries)

    SectionHeader("选集", Modifier.padding(top = 4.dp))
    if (ranges.size > 1) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ranges) { i, r ->
                val on = i == g
                Text(EpisodeGroups.label(r, d.episodes), color = if (on) c.text else c.secondary, fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.text.copy(if (on) 0.14f else 0.06f))
                        .clickable { setGroup(i) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
    ranges.getOrNull(g)?.let { r ->
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { i ->
                        val ep = d.episodes[i]
                        val cur = resume?.episodeIndex == i
                        Box(
                            Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (cur) c.accent.copy(0.17f) else glass).clickable { onPlay(sid, ep.ep) },
                            contentAlignment = Alignment.Center,
                        ) { Text("${ep.ep}", color = if (cur) c.accent else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = IdjThemeColors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun RelatedShelf(items: List<HGSeries>, currentId: String, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("系列 · 共${items.size}季") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("搜全系列", color = c.secondary, fontSize = 15.sp)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { s ->
                val on = s.seriesId == currentId
                Column(Modifier.width(104.dp).clickable(enabled = !on) { onOpen(s.seriesId) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(104.dp, 139.dp).clip(RoundedCornerShape(10.dp)).background(c.elevated)
                        .border(if (on) 2.5.dp else 0.8.dp, if (on) c.accent else c.text.copy(0.12f), RoundedCornerShape(10.dp))) {
                        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (on) Text("当前", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(50)).background(c.bg.copy(0.85f)).padding(horizontal = 6.dp, vertical = 3.dp))
                        if (s.episodeCount > 0) Text("${s.episodeCount}集", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(0.55f)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                    Text(s.title, color = if (on) c.text else c.secondary, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
