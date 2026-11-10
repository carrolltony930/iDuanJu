package app.iduanju.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.manga.*
import app.iduanju.ui.components.GlassChip
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Cover(url: String, headers: Map<String, String>, mod: Modifier, corner: Int = 10) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.crossfade(true).build() }
    AsyncImage(req, null, mod.clip(RoundedCornerShape(corner.dp)).background(IdjThemeColors.elevated), contentScale = ContentScale.Crop)
}

/** 「漫画」 home page (iOS MangaHomeView, embedded): source chips, search, categories, 继续阅读, 3-column grid. */
@Composable
fun MangaHomePage(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    var source by remember { mutableStateOf(store.providers[0].source) }
    val p = store.provider(source)
    var cat by remember(source) { mutableStateOf(p.categories[0]) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf<String?>(null) }
    val items = remember(source, cat, searching) { mutableStateListOf<MangaItem>() }
    var page by remember(source, cat, searching) { mutableIntStateOf(0) }
    var hasMore by remember(source, cat, searching) { mutableStateOf(true) }
    var loading by remember(source, cat, searching) { mutableStateOf(false) }
    var error by remember(source, cat, searching) { mutableStateOf<String?>(null) }
    suspend fun more() {
        if (loading || !hasMore) return
        loading = true
        try {
            val r = withContext(Dispatchers.IO) { searching?.let { p.search(it, page + 1) } ?: p.list(cat, page + 1) }
            val seen = items.map { it.id }.toHashSet(); items.addAll(r.items.filter { seen.add(it.id) }); page += 1; hasMore = r.hasMore; error = null
        } catch (e: Exception) { error = e.message ?: "加载失败"; hasMore = false } finally { loading = false }
    }
    LaunchedEffect(source, cat, searching) { more() }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) more() }

    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(3) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { store.providers.forEach { s -> GlassChip(s.label, s.source == source) { if (s.source != source) { source = s.source; searching = null; query = "" } } } }
                Row(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(c.elevated).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索${p.label}", color = c.secondary, fontSize = 15.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searching = query.trim().ifEmpty { null } }), modifier = Modifier.fillMaxWidth())
                    }
                    if (searching != null) Text("取消", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { query = ""; searching = null })
                }
                if (searching == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(p.categories) { k -> GlassChip(k.title, k.id == cat.id) { cat = k } } }
                val recent = store.recent.take(10)
                if (recent.isNotEmpty() && searching == null) {
                    Text("继续阅读", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.item.id }) { r ->
                            Column(Modifier.width(84.dp).clickable { onOpen(store.put(r.item)) }) {
                                Cover(r.item.cover, store.provider(r.item.source).imageHeaders, Modifier.size(84.dp, 112.dp), 8)
                                Text(r.item.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.chapterTitle, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        items(items, key = { it.id }) { m ->
            Column(Modifier.clickable { onOpen(store.put(m)) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cover(m.cover, p.imageHeaders, Modifier.fillMaxWidth().aspectRatio(3f / 4))
                Text(m.title, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.tags.take(2).joinToString(" · ").ifEmpty { m.author }, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when { loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)); error != null -> Text(error!!, color = c.accentWarm, fontSize = 12.sp) }
                Text("来源：${p.note}", color = c.secondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** iOS MangaDetailView: cover + meta, 收藏 / 开始阅读 (续读), desc, chapter list with 正序/倒序. */
@Composable
fun MangaDetailScreen(id: String, onBack: () -> Unit, onRead: (String, Int, Int) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val item = store.cache[id] ?: store.progress[id]?.item ?: store.favorites.firstOrNull { it.id == id } ?: run { onBack(); return }
    val p = store.provider(item.source)
    var detail by remember { mutableStateOf<MangaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asc by remember { mutableStateOf(true) }
    LaunchedEffect(id) { runCatching { withContext(Dispatchers.IO) { p.detail(item) } }.onSuccess { detail = it; store.cache[id] = it.item; MangaReaderState.detail = it }.onFailure { error = it.message } }
    val shown = detail?.item ?: item
    val prog = store.progress[id]
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text) }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Cover(shown.cover, p.imageHeaders, Modifier.size(110.dp, 147.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(shown.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (shown.author.isNotEmpty()) Text(shown.author, color = c.secondary, fontSize = 15.sp)
                        Text((listOf(detail?.status ?: "") + shown.tags).filter { it.isNotEmpty() }.take(5).joinToString(" · "), color = c.secondary, fontSize = 12.sp)
                        Text(p.label, color = c.accent, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            val fav = store.isFavorite(shown)
                            OutlinedButton({ store.toggleFavorite(shown) }) { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = c.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (fav) "已收藏" else "收藏", color = c.accent) }
                            detail?.takeIf { it.chapters.isNotEmpty() }?.let { d ->
                                val i = prog?.let { pr -> d.chapters.indexOfFirst { it.chapterId == pr.chapterId } } ?: -1
                                Button({ if (i >= 0) onRead(id, i, prog!!.page) else onRead(id, 0, 0) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) {
                                    Text(if (i >= 0) "续读 " + prog!!.chapterTitle.take(8) else "开始阅读")
                                }
                            }
                        }
                    }
                }
            }
            detail?.desc?.takeIf { it.isNotEmpty() }?.let { item { Text(it, color = c.secondary, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp)) } }
            error?.let { item { Text(it, color = c.accentWarm, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)) } }
            val d = detail
            if (d == null && error == null) item { Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.secondary) } }
            if (d != null) {
                item {
                    Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("章节（${d.chapters.size}）", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
                        Text(if (asc) "正序" else "倒序", color = c.accent, fontSize = 15.sp, modifier = Modifier.clickable { asc = !asc })
                    }
                }
                items(if (asc) d.chapters else d.chapters.reversed(), key = { it.chapterId }) { ch ->
                    val reading = prog?.chapterId == ch.chapterId
                    Text(ch.title, color = if (reading) c.accent else c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onRead(id, ch.index, if (reading) prog!!.page else 0) }.padding(vertical = 11.dp))
                    HorizontalDivider(color = c.text.copy(0.06f))
                }
            }
        }
    }
}

/** The chapter list travels from detail to reader in memory. */
object MangaReaderState { var detail: MangaDetail? = null }

/** iOS MangaReaderView: vertical webtoon scroll (default) or horizontal paging; chrome toggles on tap; progress recorded. */
@Composable
fun MangaReaderScreen(id: String, startIndex: Int, startPage: Int, onBack: () -> Unit) {
    val store = IdjApp.instance.manga
    val d = MangaReaderState.detail?.takeIf { it.item.id == id } ?: run { onBack(); return }
    val p = store.provider(d.item.source)
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE) }
    var vertical by remember { mutableStateOf(sp.getBoolean("stv.manga.vertical", true)) }
    var index by remember { mutableIntStateOf(startIndex) }
    var pages by remember(index) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var current by remember(index) { mutableIntStateOf(0) }
    val chapter = d.chapters[index]
    LaunchedEffect(index) { runCatching { withContext(Dispatchers.IO) { p.pages(d.item, chapter) } }.onSuccess { pages = it }.onFailure { error = it.message } }
    LaunchedEffect(current, pages.size) { if (pages.isNotEmpty()) store.record(d.item, chapter, current, pages.size) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (pages.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) { Text(error!!, color = Color.White); TextButton({ error = null; index = index }) { Text("重试") } } else CircularProgressIndicator(color = Color.White)
            }
        } else if (vertical) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = if (index == startIndex) startPage.coerceIn(0, pages.size - 1) else 0)
            LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { current = it.coerceAtMost(pages.size - 1) } }
            LazyColumn(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { chrome = !chrome }, state = list) {
                itemsIndexed(pages, key = { i, u -> "$i$u" }) { _, u -> PageImage(u, p.imageHeaders) }
                item { ChapterEnd(d, index) { index = it } }
            }
        } else {
            val pager = rememberPagerState(if (index == startIndex) startPage.coerceIn(0, pages.size - 1) else 0) { pages.size + 1 }
            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { current = it.coerceAtMost(pages.size - 1) } }
            HorizontalPager(pager, Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { chrome = !chrome }) { i ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (i < pages.size) PageImage(pages[i], p.imageHeaders) else ChapterEnd(d, index) { index = it } }
            }
        }
        if (chrome) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowDown, "关闭", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(d.item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter.title, color = Color.White.copy(0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { vertical = !vertical; sp.edit().putBoolean("stv.manga.vertical", vertical).apply() }, contentAlignment = Alignment.Center) {
                    Icon(if (vertical) Icons.Filled.ViewDay else Icons.Filled.ViewCarousel, if (vertical) "切换为左右翻页" else "切换为上下滚动", tint = Color.White)
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 10.dp).fillMaxWidth().clip(CircleShape).background(Color.White.copy(0.15f)).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SkipPrevious, "上一章", tint = if (index > 0) Color.White else Color.White.copy(0.3f), modifier = Modifier.clickable(enabled = index > 0) { index-- })
                Spacer(Modifier.weight(1f))
                Text(if (pages.isEmpty()) "" else "${current + 1} / ${pages.size}", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.SkipNext, "下一章", tint = if (index + 1 < d.chapters.size) Color.White else Color.White.copy(0.3f), modifier = Modifier.clickable(enabled = index + 1 < d.chapters.size) { index++ })
            }
        }
    }
}

@Composable
private fun PageImage(url: String, headers: Map<String, String>) {
    val ctx = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val req = remember(url, retry) { ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build() }
    SubcomposeAsyncImage(req, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
        loading = { Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } },
        error = { Box(Modifier.fillMaxWidth().height(300.dp).clickable { retry++ }, contentAlignment = Alignment.Center) { Text("加载失败，点我重试", color = Color.White.copy(0.6f)) } })
}

@Composable
private fun ChapterEnd(d: MangaDetail, index: Int, go: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("本章完", color = Color.White.copy(0.7f))
        if (index + 1 < d.chapters.size) Button({ go(index + 1) }, colors = ButtonDefaults.buttonColors(containerColor = IdjThemeColors.accent, contentColor = Color.White)) { Text("下一章：" + d.chapters[index + 1].title, maxLines = 1) }
        else Text("已是最新一章", color = Color.White.copy(0.5f), fontSize = 12.sp)
    }
}
