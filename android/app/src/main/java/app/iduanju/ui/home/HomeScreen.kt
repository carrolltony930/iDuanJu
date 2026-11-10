package app.iduanju.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.components.CardShape
import app.iduanju.ui.components.GlassChip
import app.iduanju.ui.components.PosterShape
import app.iduanju.ui.components.posterBadge
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    val app = IdjApp.instance
    var genre by remember { mutableStateOf(HGRankGenre.all) }
    var sub by remember { mutableStateOf(HGRankSub.hot) }
    var items by remember { mutableStateOf<List<HGSeries>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(genre, sub) {
        loading = true; err = null
        try {
            items = withContext(Dispatchers.IO) { app.hongguo.rank(sub, genre).items }
        } catch (e: Exception) { err = e.message; items = emptyList() }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        // Search pill
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(c.elevated)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索你想看的作品", color = c.secondary, fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.Tune, "筛选", tint = c.secondary)
        }

        // Genre bar
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HGRankGenre.entries.forEach { g ->
                val on = g == genre
                Column(
                    Modifier.clickable { genre = g; sub = g.subs.first() }.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(g.label, color = if (on) c.text else c.secondary, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.height(2.dp).width(if (on) 28.dp else 0.dp).background(c.accent, RoundedCornerShape(1.dp)))
                }
            }
        }

        // Sub chips
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genre.subs.forEach { s -> GlassChip(s.label, s == sub) { sub = s } }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err!!, color = c.secondary) }
            else -> {
                val hero = items.firstOrNull()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (hero != null) {
                        item(span = { GridItemSpan(3) }) { HeroCard(hero, sub.label, onOpenDetail) }
                    }
                    item(span = { GridItemSpan(3) }) {
                        Text(sub.label + "榜", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    items(items.drop(1), key = { it.seriesId }) { s ->
                        PosterCard(s, onOpenDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HGSeries, rankName: String, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Box(
        Modifier.fillMaxWidth().height(200.dp).clip(CardShape).background(c.surface).clickable { onOpen(s.seriesId) },
    ) {
        AsyncImage(
            ImageRequest.Builder(LocalContext.current).data(s.cover).crossfade(true).build(),
            null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f,
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c.bg.copy(0.85f), Color.Transparent))))
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(100.dp).fillMaxHeight().clip(PosterShape)) {
                AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Badge(posterBadge(s.episodeCount), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("$rankName · TOP ${s.rank.coerceAtLeast(1)}", color = c.accentWarm, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(s.title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("${s.episodeCount}集" + if (s.playCount > 0) " · ${formatCount(s.playCount)}热度" else "", color = c.secondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(c.text).clickable { onOpen(s.seriesId) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = c.bg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看详情", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun PosterCard(s: HGSeries, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.clickable { onOpen(s.seriesId) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4.2f).clip(PosterShape).background(c.elevated)) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Badge(posterBadge(s.episodeCount), Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(s.title, color = c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text, color = Color.White, fontSize = 10.sp,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}
