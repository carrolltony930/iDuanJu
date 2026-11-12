package app.iduanju.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGPanel
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.home.HomeFmt
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** iOS CategoryView: 榜单 (pill genre + sub, numbered grid) / 筛选找片 (filter panel). */
@Composable
fun CategoryScreen(onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var filterMode by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf(HGRankGenre.all) }
    var sub by remember { mutableStateOf(HGRankSub.hotSearch) }
    var panel by remember { mutableStateOf<HGPanel?>(null) }
    var panelErr by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, String>() }
    val items = remember { mutableStateListOf<HGSeries>() }
    var nextOffset by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableIntStateOf(0) }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        if (reset) { items.clear(); nextOffset = 0; session = null; hasMore = false; gen++ }
        val myGen = gen
        loading = true; err = null
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (!filterMode) IdjApp.instance.hongguo.rank(sub, genre, nextOffset, items.map { it.seriesId })
                    else IdjApp.instance.hongguo.category(selected.values.filter { it.isNotEmpty() }.sorted(), nextOffset, session)
                }
                if (myGen != gen) return@launch
                val seen = items.map { it.seriesId }.toSet()
                items.addAll(p.items.filter { it.seriesId !in seen })
                nextOffset = p.nextOffset ?: (nextOffset + p.items.size); session = p.sessionId; hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: Exception) { if (myGen == gen) err = e.message ?: "加载失败" }
            if (myGen == gen) loading = false
        }
    }
    LaunchedEffect(filterMode, genre, sub) {
        if (filterMode && panel == null) try { panel = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.panel() } } catch (e: Exception) { panelErr = e.message }
        if (!genre.subs.contains(sub)) sub = genre.subs[0]
        load(true)
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (hasMore && !loading && last >= items.size - 3) load(false) }
    }

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().background(c.bg).statusBarsPadding(), state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(3) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf("榜单" to false, "筛选找片" to true).forEach { (label, f) ->
                        val on = filterMode == f
                        Text(label, color = if (on) c.accent else c.secondary, fontSize = if (on) 22.sp else 17.sp,
                            fontWeight = if (on) FontWeight.Black else FontWeight.Normal, modifier = Modifier.clickable { filterMode = f })
                    }
                }
                if (!filterMode) {
                    Pills(HGRankGenre.entries.map { it.label }, HGRankGenre.entries.indexOf(genre)) { genre = HGRankGenre.entries[it] }
                    Pills(genre.subs.map { it.label }, genre.subs.indexOf(sub)) { sub = genre.subs[it] }
                } else {
                    panel?.rows?.forEach { row ->
                        val opts = listOf("" to "全部") + row.items.map { it.id to it.name }
                        Pills(opts.map { it.second }, opts.indexOfFirst { it.first == (selected[row.type] ?: "") }.coerceAtLeast(0)) {
                            selected[row.type] = opts[it].first; load(true)
                        }
                    } ?: panelErr?.let { Text(it, color = c.secondary, fontSize = 13.sp) }
                }
            }
        }
        itemsIndexed(items, key = { _, s -> s.seriesId }) { i, s -> NumberedPosterCell(s, if (filterMode) null else i + 1, onOpenDetail) }
        item(span = { GridItemSpan(3) }) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    err != null -> Text("$err · 点击重试", color = c.secondary, fontSize = 13.sp, modifier = Modifier.clickable { load(items.isEmpty()) })
                    !hasMore && items.isNotEmpty() -> Text("没有更多了", color = c.secondary, fontSize = 12.sp)
                }
            }
        }
    }
}

/** iOS PillTabs: 15sp, capsule text-on-selected / glass. */
@Composable
private fun Pills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = IdjThemeColors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(labels.size) { i ->
            val on = i == selected
            Text(labels[i], color = if (on) c.bg else c.text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.text else c.text.copy(0.07f)).clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

private fun rankColor(r: Int): Color = when (r) { 1 -> Color(1f, 0.27f, 0.2f); 2 -> Color(1f, 0.55f, 0.15f); 3 -> Color(1f, 0.78f, 0.2f); else -> Color.Black }

/** iOS NumberedPosterCell: big italic 2-digit rank with coloured offset shadow, 新剧 tag, meta bottom-right. */
@Composable
private fun NumberedPosterCell(s: HGSeries, rank: Int?, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.clickable { onOpen(s.seriesId) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(c.elevated)) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(0.55f))))
            if (rank != null) Text("%02d".format(rank), modifier = Modifier.padding(start = 4.dp).offset(y = (-4).dp),
                style = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    shadow = Shadow(rankColor(rank), Offset(5f, 5f), 0f)))
            if (s.isNew) Text("新剧", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
            val m = HomeFmt.metaLine(s)
            if (m.isNotEmpty()) Text(m, color = Color.White.copy(0.9f), fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Text(s.title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
