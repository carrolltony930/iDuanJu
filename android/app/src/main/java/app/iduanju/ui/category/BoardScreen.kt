package app.iduanju.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.home.BoardSpec
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** iOS BoardListView: the full board behind a home section's 「全部」 (numbered grid, paged). */
@Composable
fun BoardScreen(board: BoardSpec, onBack: () -> Unit, onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<HGSeries>() }
    var nextOffset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (loading) return
        loading = true; err = null
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.rank(board.sub, board.genre, nextOffset, items.map { it.seriesId }) }
                val seen = items.map { it.seriesId }.toSet()
                items.addAll(p.items.filter { it.seriesId !in seen })
                nextOffset = p.nextOffset ?: (nextOffset + p.items.size); hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: Exception) { err = e.message ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (items.isEmpty()) load() }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (hasMore && !loading && err == null && last >= items.size - 3) load() }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text) }
            Text(board.title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { _, s -> s.seriesId }) { i, s -> NumberedPosterCell(s, i + 1, onOpenDetail) }
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        err != null -> Text("$err · 点击重试", color = c.secondary, fontSize = 13.sp, modifier = Modifier.clickable { load() })
                        !hasMore && items.isNotEmpty() -> Text("没有更多了", color = c.secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
