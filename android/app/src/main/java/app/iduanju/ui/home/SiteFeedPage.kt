package app.iduanju.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS HomeFeedPage for `.site(cms)`. */
@Composable
fun SiteFeedPage(site: CMSSite, onOpen: (String) -> Unit) {
    FeedGrid("cms:" + site.key, listOf("" to "最新") + site.categories.map { it.id to it.name }, { cat, page ->
        val o = MacCMS.fetch(site.api, buildMap { put("ac", "detail"); put("pg", page.toString()); if (cat.isNotEmpty()) put("t", cat) })
        if (cat.isEmpty() && page == 1) MacCMS.parseCategories(o).takeIf { it.isNotEmpty() }?.let { IdjApp.instance.cms.cacheCategories(site.key, it) }
        MacCMS.page(o, site.key, page)
    }, onOpen)
}

/** iOS HomeFeedPage for `.site(plugin)` (JSVideoProvider): 推荐 + the categories `home()` returned. */
@Composable
fun PluginFeedPage(p: app.iduanju.plugin.PluginStore.Installed, onOpen: (String) -> Unit) {
    var cats by remember(p.id) { mutableStateOf(listOf("" to "推荐")) }
    FeedGrid("plugin:" + p.id, cats, { cat, page ->
        val v = IdjApp.instance.plugins.video(p.id)
        try {
            val r = v.list(cat, page)
            if (cat.isEmpty()) v.categories.takeIf { it.isNotEmpty() }?.let { cs -> cats = listOf("" to "推荐") + cs.map { it.id to it.name } }
            r
        } catch (e: app.iduanju.core.plugin.PluginException) { throw Exception(e.friendly) }
    }, onOpen)
}

/** 最新/推荐 + category chips, 3-column poster grid with paging. */
@Composable
fun FeedGrid(key: String, cats: List<Pair<String, String>>, load: (String, Int) -> app.iduanju.core.hongguo.HGPage, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    var sel by remember(key) { mutableIntStateOf(0) }
    val items = remember(key, sel) { mutableStateListOf<HGSeries>() }
    var page by remember(key, sel) { mutableIntStateOf(1) }
    var hasMore by remember(key, sel) { mutableStateOf(true) }
    var loading by remember(key, sel) { mutableStateOf(false) }
    var error by remember(key, sel) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    suspend fun more() {
        if (loading || !hasMore) return
        loading = true
        try {
            val cat = cats.getOrNull(sel)?.first ?: ""
            val p = withContext(Dispatchers.IO) { load(cat, page) }
            val seen = items.map { it.seriesId }.toHashSet()
            items.addAll(p.items.filter { seen.add(it.seriesId) })
            hasMore = p.hasMore; page += 1; error = null
        } catch (e: Exception) { error = e.message ?: "加载失败"; hasMore = false }
        finally { loading = false }
    }
    LaunchedEffect(key, sel) { more() }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) more() }

    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(3) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(cats) { i, (_, name) ->
                    val on = i == sel
                    Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (on) c.text else c.text.copy(0.08f))
                        .clickable { sel = i }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(name, color = if (on) c.bg else c.text.copy(0.85f), fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        items(items, key = { it.seriesId }) { HomePosterCard(it, onOpen) }
        item(span = { GridItemSpan(3) }) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    error != null -> Text(error!!, color = c.secondary, fontSize = 13.sp)
                    items.isEmpty() -> Text("暂无内容", color = c.secondary, fontSize = 13.sp)
                }
            }
        }
    }
}
