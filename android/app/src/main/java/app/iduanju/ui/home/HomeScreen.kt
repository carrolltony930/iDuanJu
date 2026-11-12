package app.iduanju.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mirrors iOS HomeSource / HomeTab (HomeView.swift). */
enum class HomeSource(val label: String) { hongguo("红果"), huangguo("黄果"), ai("AI剧"), channels("频道"), manga("漫画") }

sealed class HomeTab(val subLabel: String) {
    data class Hongguo(val g: HGRankGenre) : HomeTab(
        when (g) { HGRankGenre.all -> "全部榜单"; HGRankGenre.ai -> "红果 AI剧"; else -> g.label }
    )
    data object ActorRank : HomeTab("剧星榜")
}

fun HomeSource.subs(): List<HomeTab> = when (this) {
    HomeSource.hongguo -> listOf(HomeTab.Hongguo(HGRankGenre.all), HomeTab.Hongguo(HGRankGenre.human), HomeTab.Hongguo(HGRankGenre.comic), HomeTab.ActorRank)
    HomeSource.ai -> listOf(HomeTab.Hongguo(HGRankGenre.ai))
    else -> emptyList()
}

data class BoardSpec(val genre: HGRankGenre, val sub: HGRankSub) {
    val id get() = "hg-${genre.name}-${sub.name}"
    val title get() = (if (genre == HGRankGenre.all) "" else genre.label) + sub.label + "榜"
}

/** First-page cache per board (iOS BoardCache). */
object BoardCache {
    val items = mutableStateMapOf<String, List<HGSeries>>()
    val failed = mutableStateMapOf<String, String>()
    private val loading = HashSet<String>()
    suspend fun load(b: BoardSpec, force: Boolean = false) {
        synchronized(loading) { if (b.id in loading || (!force && items.containsKey(b.id))) return; loading.add(b.id) }
        try {
            val r = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.rank(b.sub, b.genre).items }
            items[b.id] = r; failed.remove(b.id)
        } catch (e: Exception) { failed[b.id] = e.message ?: "加载失败"; android.util.Log.w("IDJ", "board ${b.id} failed: $e") }
        finally { synchronized(loading) { loading.remove(b.id) } }
    }
}

object HomeFmt {
    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
        else -> n.toString()
    }
    fun metaLine(s: HGSeries): String = when {
        s.episodeCount > 0 && s.playCount > 0 -> "${s.episodeCount}集 · ${count(s.playCount)}热度"
        s.episodeCount > 0 -> "${s.episodeCount}集"
        s.playCount > 0 -> count(s.playCount) + "热度"
        else -> s.subtitle ?: ""
    }
    fun meta(s: HGSeries): String {
        val genres = s.tags.take(2).joinToString(" · ")
        val parts = listOfNotNull(genres.ifEmpty { null }, if (s.episodeCount > 0) "${s.episodeCount}集" else null)
        return if (parts.isEmpty()) metaLine(s) else parts.joinToString(" · ")
    }
    /** Only 「更新至N集 / 全N集」. */
    fun progress(s: HGSeries): String? {
        s.subtitle?.let { if (it.contains("更新至") || it.startsWith("全")) return it }
        return if (s.episodeCount > 0) "全${s.episodeCount}集" else null
    }
}

fun formatCount(n: Long) = HomeFmt.count(n)

@Composable
fun HomeScreen(onOpenDetail: (String) -> Unit, onSearch: () -> Unit = {}, onPlay: (String) -> Unit = {}, onOpenManga: (String) -> Unit = {}) {
    val c = IdjThemeColors
    // iOS HomeSource.all: [红果, 黄果, AI剧] + visible sites (采集站 / plugins) + [频道, 漫画]
    val sites = IdjApp.instance.cms.visibleSites
    val plugins = IdjApp.instance.plugins.visible(IdjApp.instance.cms.adultUnlocked).filter { it.type == "video" || it.type == "shortdrama" }
    val pages: List<Any> = listOf<Any>(HomeSource.hongguo, HomeSource.huangguo, HomeSource.ai) + sites + plugins + listOf(HomeSource.channels, HomeSource.manga)
    val labels = pages.map { when (it) { is HomeSource -> it.label; is app.iduanju.core.cms.CMSSite -> it.name; else -> (it as app.iduanju.plugin.PluginStore.Installed).name } }
    val pager = rememberPagerState { pages.size }
    LaunchedEffect(HomeJump.label, pages.size) {
        val i = labels.indexOf(HomeJump.label ?: return@LaunchedEffect)
        if (i >= 0) { pager.scrollToPage(i); HomeJump.label = null }
    }
    val scope = rememberCoroutineScope()
    val heroCovers = remember { mutableStateMapOf<String, String>() }
    val source = pages[pager.currentPage.coerceIn(0, pages.size - 1)]
    val sourceKey = labels[pager.currentPage.coerceIn(0, pages.size - 1)]
    val onPoster = heroCovers[sourceKey] != null
    val ink = if (onPoster) Color.White else c.text

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // atmosphere: hero poster blurred across the top, fading into the page colour
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .graphicsLayer { alpha = 0.99f }
                .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to c.bg))
            },
        ) {
            if (onPoster) {
                Box(Modifier.fillMaxSize().background(Color(0xFF141A26)))
                BlurBackdrop(heroCovers[sourceKey], Modifier.fillMaxSize(), 48.dp, 0.75f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(0.35f), c.bg))))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.elevated, c.bg))))
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header: search + filter capsule
            Column(Modifier.padding(top = 6.dp)) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(ink.copy(if (onPoster) 0.18f else 0.07f))
                        .border(1.dp, ink.copy(0.14f), RoundedCornerShape(50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("搜索你想看的作品", color = ink.copy(0.8f), fontSize = 16.sp, maxLines = 1)
                    }
                    Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FilterList, null, tint = ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("筛选", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(2.dp))
                var sheet by remember { mutableStateOf(false) }
                ChannelBar(labels, pager.currentPage, ink, onMore = { sheet = true }) { scope.launch { pager.animateScrollToPage(it) } }
                if (sheet) ChannelSheet(pages, labels, pager.currentPage, onDismiss = { sheet = false }) { page, sub ->
                    sheet = false
                    if (sub != null) HomeSubSel.map[labels[page]] = sub
                    scope.launch { pager.animateScrollToPage(page) }
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                val any = pages[page]
                if (any is app.iduanju.core.cms.CMSSite) { SiteFeedPage(any, onOpenDetail); return@HorizontalPager }
                if (any is app.iduanju.plugin.PluginStore.Installed) { PluginFeedPage(any, onOpenDetail); return@HorizontalPager }
                val s = any as HomeSource
                if (s == HomeSource.huangguo) { app.iduanju.huangguo.HuangguoFeedPage(onOpenDetail); return@HorizontalPager }
                if (s == HomeSource.manga) { app.iduanju.manga.MangaHomePage(onOpenManga); return@HorizontalPager }
                if (s == HomeSource.channels) { app.iduanju.tg.ChannelFeedPage(onPlay); return@HorizontalPager }
                if (s == HomeSource.ai && app.iduanju.IdjApp.instance.cms.adultUnlocked) {
                    // iOS AI剧: 红果 AI剧 | 黄果 AI 站 (the latter only behind the adult gate)
                    val sel = HomeSubSel.map[s.label] ?: 0
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("红果 AI剧", "黄果 AI 站").forEachIndexed { i, t ->
                                val on = i == sel
                                Box(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (on) c.text else c.text.copy(0.1f)).clickable { HomeSubSel.map[s.label] = i }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                    Text(t, color = if (on) c.bg else c.text.copy(0.8f), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            if (sel == 1) app.iduanju.huangguo.HuangguoFeedPage(onOpenDetail, aiOnly = true)
                            else FeedPage(s, onOpenDetail) { cover -> if (cover == null) heroCovers.remove(s.label) else heroCovers[s.label] = cover }
                        }
                    }
                } else if (s.subs().isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("「${s.label}」Android 版尚未移植", color = c.secondary, fontSize = 14.sp)
                    }
                } else {
                    FeedPage(s, onOpenDetail) { cover -> if (cover == null) heroCovers.remove(s.label) else heroCovers[s.label] = cover }
                }
            }
        }
    }
}

/**
 * iOS blurred cover backdrop. Modifier.blur only works on API 31+; on older systems decode a tiny
 * (16×22) thumbnail and let bilinear upscaling soften it, which reads as a heavy blur.
 */
@Composable
private fun BlurBackdrop(url: String?, modifier: Modifier, radius: androidx.compose.ui.unit.Dp, alpha: Float) {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        AsyncImage(url, null, modifier.blur(radius), contentScale = ContentScale.Crop, alpha = alpha)
    } else {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val req = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(16, 22).memoryCacheKey("blur:$url").build() }
        AsyncImage(req, null, modifier, contentScale = ContentScale.Crop, alpha = alpha, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    }
}

/** Sub-tab chosen from the 频道 sheet, per home page label. */
object HomeSubSel { val map = androidx.compose.runtime.mutableStateMapOf<String, Int>() }
/** One-shot request from another tab (我的 → Telegram 频道 / 漫画) to show a home source page. */
object HomeJump { var label by androidx.compose.runtime.mutableStateOf<String?>(null) }

/** iOS HomeChannelSheet: 「频道」 title + 完成, one section per page with its sub-channels as a 3-column grid of chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSheet(pages: List<Any>, labels: List<String>, current: Int, onDismiss: () -> Unit, onPick: (Int, Int?) -> Unit) {
    val c = IdjThemeColors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = c.surface, dragHandle = { BottomSheetDefaults.DragHandle(color = c.text.copy(0.25f)) }) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("频道", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(50)).background(c.text.copy(0.1f)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("完成", color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pages.forEachIndexed { pi, p ->
                val subs: List<String> = when (p) {
                    is HomeSource -> when (p) {
                        HomeSource.huangguo -> listOf("视频站", "App 源")
                        HomeSource.ai -> p.subs().map { it.subLabel } + if (app.iduanju.IdjApp.instance.cms.adultUnlocked) listOf("黄果 AI 站") else emptyList()
                        HomeSource.channels, HomeSource.manga -> emptyList()
                        else -> p.subs().map { it.subLabel }
                    }
                    else -> listOf(labels[pi])
                }
                if (subs.isEmpty()) return@forEachIndexed
                Text(labels[pi], color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                subs.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { j, t ->
                            val si = subs.indexOf(t)
                            val on = pi == current && (HomeSubSel.map[labels[pi]] ?: 0) == si
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent.copy(0.16f) else c.text.copy(0.07f))
                                    .clickable { onPick(pi, if (p is HomeSource && p != HomeSource.huangguo) si else null) }, contentAlignment = Alignment.Center) {
                                Text(t, color = if (on) c.accent else c.text, fontSize = 15.sp, maxLines = 1)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** iOS HomeChannelBar: unselected 17 secondary, selected 20 bold + 18x3 accent capsule; selected auto-centred. */
@Composable
private fun ChannelBar(items: List<String>, selected: Int, ink: Color, onMore: () -> Unit = {}, onSelect: (Int) -> Unit) {
    val c = IdjThemeColors
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        if (item != null) {
            val center = (info.viewportEndOffset - info.viewportStartOffset) / 2
            state.animateScrollBy((item.offset + item.size / 2 - center).toFloat())
        } else state.animateScrollToItem(selected)
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(state = state, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(items) { i, label ->
                val on = i == selected
                Box(Modifier.heightIn(min = 44.dp).clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) ink else ink.copy(0.62f), fontSize = if (on) 20.sp else 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    if (on) Box(Modifier.align(Alignment.BottomCenter).offset(y = (-2).dp).size(18.dp, 3.dp).clip(RoundedCornerShape(50)).background(c.accent))
                }
            }
            item { Spacer(Modifier.width(12.dp)) }
        }
        Box(Modifier.size(44.dp).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.KeyboardArrowDown, "展开全部频道", tint = ink.copy(0.85f))
        }
    }
}


@Composable
private fun FeedPage(source: HomeSource, onOpen: (String) -> Unit, onHero: (String?) -> Unit) {
    val c = IdjThemeColors
    val subs = source.subs()
    var tab by remember { mutableStateOf(subs.getOrElse(HomeSubSel.map[source.label] ?: 0) { subs.first() }) }
    LaunchedEffect(HomeSubSel.map[source.label]) { HomeSubSel.map[source.label]?.let { i -> subs.getOrNull(i)?.let { tab = it } } }
    val boards = (tab as? HomeTab.Hongguo)?.g?.let { g -> g.subs.map { BoardSpec(g, it) } } ?: emptyList()
    val heroPicks = boards.firstOrNull()?.let { BoardCache.items[it.id] }?.take(5) ?: emptyList()
    var heroIndex by remember(tab) { mutableIntStateOf(0) }
    LaunchedEffect(tab) { boards.firstOrNull()?.let { BoardCache.load(it) } }
    LaunchedEffect(heroPicks, heroIndex) { onHero(heroPicks.getOrNull(heroIndex)?.cover) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + 72.dp)) {
        if (subs.size > 1) {
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subs.forEach { t ->
                    val on = t == tab
                    Box(
                        Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (on) c.text else c.text.copy(0.1f))
                            .clickable { tab = t }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.subLabel, color = if (on) c.bg else c.text.copy(0.8f), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        if (tab == HomeTab.ActorRank) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text("剧星榜 Android 版待移植", color = c.secondary) }
            return@Column
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (heroPicks.isNotEmpty()) {
                PosterHero(boards.first().title, heroPicks, heroIndex, { heroIndex = it }, onOpen)
            } else HeroSkeleton()
            val cw = IdjApp.instance.library.continueWatching
            if (source == HomeSource.hongguo && cw.isNotEmpty()) ContinueRow(cw, onOpen)
            val heroIds = heroPicks.map { it.seriesId }.toSet()
            boards.forEachIndexed { n, b ->
                LaunchedEffect(b.id) { BoardCache.load(b) }
                val items = BoardCache.items[b.id]
                val rest = if (n == 0) items?.filter { it.seriesId !in heroIds } else items
                HomeSection(b.title, if (BoardCache.failed.containsKey(b.id) && items == null) "加载失败，下拉重试" else null, rest?.take(6), onOpen)
            }
        }
    }
}

@Composable
private fun HeroSkeleton() {
    val c = IdjThemeColors
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(228.dp).clip(RoundedCornerShape(22.dp)).background(c.surface))
}

/** iOS PosterHero: 228 high, radius 22, blurred backdrop, 147x196 poster, auto-advance 6s, dots bottom-right. */
@Composable
private fun PosterHero(board: String, picks: List<HGSeries>, index: Int, setIndex: (Int) -> Unit, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val pager = rememberPagerState { picks.size }
    LaunchedEffect(pager.currentPage) { setIndex(pager.currentPage) }
    LaunchedEffect(pager.currentPage, picks.size) {
        if (picks.size > 1) { delay(6000); pager.animateScrollToPage((pager.currentPage + 1) % picks.size) }
    }
    val cur = picks.getOrNull(index)
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(228.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(c.surface)
            .border(1.dp, c.text.copy(0.12f), RoundedCornerShape(22.dp)),
    ) {
        BlurBackdrop(cur?.cover, Modifier.fillMaxSize().scale(1.3f), 36.dp, 0.7f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(0.15f), Color.Black.copy(0.55f)))))
        // Each page fades with its distance from centre (iOS TabView page style shows one card at a time;
        // without this the neighbouring page's title / chips slide into view inside the card while swiping).
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val s = picks[i]
            Row(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - kotlin.math.abs(pager.getOffsetDistanceInPages(i)).coerceIn(0f, 1f) * 1.6f }.clickable { onOpen(s.seriesId) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(147.dp, 196.dp).shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(c.elevated)) {
                    AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    HomeFmt.progress(s)?.let { HomeBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        Text(if (board.isEmpty()) "TOP ${i + 1}" else "$board · TOP ${i + 1}", color = c.accentWarm, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(s.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                        val chips = s.tags.take(3)
                        if (chips.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                chips.forEach {
                                    Box(Modifier.height(20.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(0.14f)).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                                        Text(it, color = Color.White.copy(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    }
                                }
                            }
                        }
                        val line = HomeFmt.metaLine(s)
                        if (line.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(line, color = Color.White.copy(0.72f), fontSize = 12.sp, maxLines = 1) }
                        val blurb = s.subtitle?.trim()
                        if (!blurb.isNullOrEmpty() && blurb != HomeFmt.progress(s)) {
                            Spacer(Modifier.height(6.dp))
                            // blurb yields height first so the CTA never gets clipped on long titles
                            var fitsOneLine by remember(blurb) { mutableStateOf(true) }
                            Text(blurb, color = Color.White.copy(if (fitsOneLine) 0.66f else 0f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                // hide instead of showing a half-clipped line when the title wraps to two lines
                                onTextLayout = { r -> fitsOneLine = r.lineCount > 0 && r.getLineBottom(0) <= r.size.height + 0.5f })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF111318), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("查看详情", color = Color(0xFF111318), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (picks.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).height(20.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(0.28f)).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                picks.indices.forEach { i ->
                    val w by animateDpAsState(if (i == index) 14.dp else 5.dp, label = "dot")
                    Box(Modifier.size(w, 5.dp).clip(RoundedCornerShape(50)).background(if (i == index) Color.White else Color.White.copy(0.4f)))
                }
            }
        }
    }
}

@Composable
fun HomeBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(17.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(0.62f)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** iOS HomeSection: 17 bold title + 「全部 >」, 3 flexible columns (spacing 10 / 16), max 6 cards. */
@Composable
private fun HomeSection(title: String, note: String?, items: List<HGSeries>?, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (note != null) { Spacer(Modifier.width(12.dp)); Text(note, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部", color = c.secondary, fontSize = 13.sp)
                Icon(Icons.Filled.KeyboardArrowRight, null, tint = c.secondary, modifier = Modifier.size(14.dp))
            }
        }
        val list = items
        if (list == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(c.elevated.copy(0.6f)))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c.elevated.copy(0.6f)))
                    }
                }
            }
        } else {
            list.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { s -> Box(Modifier.weight(1f)) { HomePosterCard(s, onOpen) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** iOS HomePosterCard: 3:4 poster r10 + 0.6 border, 13 semibold 2 lines, 11 meta. */
@Composable
fun HomePosterCard(s: HGSeries, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.clickable { onOpen(s.seriesId) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(c.elevated).border(0.6.dp, c.text.copy(0.12f), RoundedCornerShape(10.dp))) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            HomeFmt.progress(s)?.let { HomeBadge(it, Modifier.align(Alignment.BottomEnd).padding(5.dp)) }
        }
        Text(s.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
        val meta = HomeFmt.meta(s)
        if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PosterCard(s: HGSeries, onOpen: (String) -> Unit) = HomePosterCard(s, onOpen)

/** iOS ContinueRow: 112x150 covers, 「看到第N集」 + accent progress capsule. */
@Composable
internal fun ContinueRow(items: List<app.iduanju.data.WatchProgress>, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("继续观看", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val go = app.iduanju.ui.LocalNavigate.current
            Text("全部", color = c.secondary, fontSize = 15.sp, modifier = Modifier.clickable { go("library/history") })
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.take(10).size) { i ->
                val p = items[i]
                Column(Modifier.width(112.dp).clickable { onOpen(p.seriesId) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(112.dp, 150.dp).clip(RoundedCornerShape(10.dp)).background(c.elevated)) {
                        AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(0.7f))))
                        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(p.resumeLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.25f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(c.accent))
                            }
                        }
                    }
                    Text(p.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
