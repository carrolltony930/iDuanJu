package app.iduanju.tg

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.tg.*
import app.iduanju.ui.components.GlassChip
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** 「频道」 home page: merged newest-first feed of subscribed public channels (iOS ChannelFeedView, embedded). */
@Composable
fun ChannelFeedPage(onPlay: (String) -> Unit) {
    val c = IdjThemeColors
    val app = IdjApp.instance
    val store = app.tg
    val adult = app.cms.adultUnlocked
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var only by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
    val all = store.feed(only, adult)
    val verdicts = all.associate { it.id to store.verdict(it) }
    val entries = if (store.mode == TGFilterMode.hide) all.filter { verdicts[it.id]?.blocked != true } else all
    val blockedCount = all.count { verdicts[it.id]?.blocked == true }
    var manage by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() }
    var suggestFor by remember { mutableStateOf<TGPost?>(null) }
    LaunchedEffect(Unit) { if (entries.isEmpty()) store.refresh(null, adult) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2200); toast = null } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item("bar") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (store.loading.isEmpty()) "${entries.size} 条" + (if (blockedCount > 0) " · 已屏蔽 $blockedCount" else "") else "刷新中…", color = c.secondary, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Tune, "频道与屏蔽规则", tint = c.text, modifier = Modifier.size(22.dp).clickable { manage = true })
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Filled.Refresh, "刷新", tint = c.text, modifier = Modifier.size(22.dp).clickable { scope.launch { store.refresh(only, adult) } })
                }
            }
            item("chips") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("全部", only == null) { only = null }
                    store.visible(adult).forEach { ch -> GlassChip(ch.displayName, only == ch.id) { only = ch.id } }
                }
            }
            store.visible(adult).filter { (only == null || it.id == only) && store.errors[it.id] != null }.forEach { ch ->
                item("err" + ch.id) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = c.accentWarm, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ch.displayName, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(store.errors[ch.id].orEmpty(), color = c.secondary, fontSize = 12.sp)
                            Text("在 Telegram 中打开", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { open("https://t.me/${ch.username}") })
                        }
                    }
                }
            }
            if (store.visible(adult).isEmpty()) item("empty") { EmptySubs(store, adult) { scope.launch { store.refresh(null, adult) } }.also { } }
            else if (entries.isEmpty()) item("wait") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    if (store.loading.isNotEmpty()) CircularProgressIndicator(color = c.secondary) else Text("下拉刷新，或点右上角 ↻", color = c.secondary, fontSize = 15.sp)
                }
            }
            items(entries, key = { it.id }) { p ->
                val v = verdicts[p.id]
                if (v?.blocked == true && p.id !in expanded) {
                    Row(Modifier.fillMaxWidth().clickable { expanded.add(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Block, null, tint = c.secondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                        Text("已屏蔽 · ${store.channel(p.channel)?.displayName ?: p.channel} · ${v.reason}", color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("显示", color = c.accent, fontSize = 12.sp)
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = c.text.copy(0.06f))
                    return@items
                }
                Box(Modifier.fillMaxWidth()) { PostCard(p, store.channel(p.channel), open, onMedia = { i ->
                    val m = p.media[i]
                    if (m.kind == TGMedia.Kind.photo) open(m.link.ifEmpty { p.permalink })
                    else if (m.video != null) onPlay(TGVideo.sid(p, i))
                    else { toast = "视频过大，网页预览不提供直链，请在 Telegram 中打开"; open(m.link.ifEmpty { p.permalink }) }
                })
                    Icon(Icons.Filled.MoreHoriz, "屏蔽", tint = c.secondary, modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(22.dp).clickable { suggestFor = p })
                }
                HorizontalDivider(Modifier.padding(start = 66.dp), color = c.text.copy(0.06f))
            }
            if (entries.isNotEmpty() && store.hasMore(only, adult)) item("more") {
                LaunchedEffect(entries.size) { store.loadMore(only, adult) }
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.secondary, modifier = Modifier.size(24.dp)) }
            }
        }
        if (manage) TGManageSheet(store, adult, onDismiss = { manage = false }) { scope.launch { store.refresh(null, adult) } }
        suggestFor?.let { p -> TGSuggestSheet(store, p, onDismiss = { suggestFor = null }) { toast = it } }
        toast?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp).clip(CircleShape).background(Color.Black.copy(0.75f)).padding(10.dp)) }
    }
}

/** iOS TGSheets: subscribed channels (add/remove) + blocklist rules (kind, pattern, toggle, delete), ad heuristics, hide/fold, import/export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TGManageSheet(store: TGStore, adult: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val c = IdjThemeColors
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var ch by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TGRule.Kind.keyword) }
    var note by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("频道", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            items(store.visible(adult), key = { "c" + it.id }) { x ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(x.displayName, color = c.text, fontSize = 14.sp)
                        Text("@${x.username}" + if (x.subscribers.isNotEmpty()) " · ${x.subscribers}" else "", color = c.secondary, fontSize = 11.sp)
                    }
                    TextButton({ store.remove(x.id) }) { Text("退订") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(ch, { ch = it }, Modifier.weight(1f), singleLine = true, label = { Text("频道用户名 / 链接") })
                    TextButton({ if (store.add(ch) != null) { ch = ""; onChanged() } else note = "无效的频道名" }) { Text("订阅") }
                }
            }
            item { HorizontalDivider(color = c.text.copy(0.08f)); Text("屏蔽规则", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("广告识别（广告标记 / 推广词 / 外链按钮）", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(store.heuristics, { store.enableHeuristics(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TGFilterMode.entries.forEach { m -> GlassChip(m.label, store.mode == m) { store.chooseMode(m) } } }
            }
            items(store.rules, key = { "r" + it.id }) { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.kind.label, color = c.secondary, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Text(r.pattern + (r.channel?.let { " · @$it" } ?: ""), color = if (r.enabled) c.text else c.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Switch(r.enabled, { store.toggleRule(r.id) })
                    TextButton({ store.removeRule(r.id) }) { Text("删除") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TGRule.Kind.entries.forEach { k -> GlassChip(k.label, kind == k) { kind = k } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(pat, { pat = it }, Modifier.weight(1f), singleLine = true, label = { Text(when (kind) { TGRule.Kind.keyword -> "关键词"; TGRule.Kind.regex -> "正则表达式"; TGRule.Kind.domain -> "example.com" }) })
                    TextButton({
                        if (kind == TGRule.Kind.regex && runCatching { Regex(pat) }.isFailure) note = "正则无效" else { store.addRule(TGRule(kind, pat.trim())); pat = "" }
                    }) { Text("添加") }
                }
                Row {
                    TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(store.export())); note = "已复制 ${store.rules.size} 条规则" }) { Text("导出到剪贴板") }
                    TextButton({ val n = store.import(clip.getText()?.text ?: ""); note = if (n > 0) "已导入 $n 条" else "剪贴板里没有可导入的规则" }) { Text("从剪贴板导入") }
                }
                note?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** iOS 「加入屏蔽」: suggested rules from a post (link domains, buttons, hashtags, first line). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TGSuggestSheet(store: TGStore, p: TGPost, onDismiss: () -> Unit, onToast: (String) -> Unit) {
    val c = IdjThemeColors
    val sug = remember(p.id) { TGFilter.suggestions(p) }
    ModalBottomSheet(onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("加入屏蔽", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (sug.isEmpty()) Text("这条消息没有可用的关键词或链接", color = c.secondary, fontSize = 13.sp)
            sug.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { store.addRule(r); onToast("已屏蔽 ${r.kind.label}：${r.pattern}"); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.kind.label, color = c.secondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    Text(r.pattern, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val chRule = TGRule(TGRule.Kind.keyword, p.text.lines().firstOrNull { it.trim().length >= 4 }?.trim()?.take(16) ?: "", channel = p.channel.lowercase())
            if (chRule.pattern.isNotEmpty()) TextButton({ store.addRule(chRule); onToast("仅在本频道屏蔽：${chRule.pattern}"); onDismiss() }) { Text("仅在本频道屏蔽首行") }
        }
    }
}

@Composable
private fun EmptySubs(store: TGStore, adult: Boolean, onAdded: () -> Unit) {
    val c = IdjThemeColors
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.Send, null, tint = c.secondary, modifier = Modifier.size(44.dp))
        Text("还没有订阅频道", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("输入公开频道用户名或链接，例如 telegram、t.me/durov", color = c.secondary, fontSize = 12.sp)
        Row(Modifier.padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(38.dp).clip(CircleShape).background(c.elevated).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("频道用户名 / 链接", color = c.secondary, fontSize = 15.sp)
                BasicTextField(text, { text = it; bad = false }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Button({ if (store.add(text) != null) { text = ""; onAdded() } else bad = true }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) { Text("订阅", fontWeight = FontWeight.SemiBold) }
        }
        if (bad) Text("无效的频道名", color = c.accentWarm, fontSize = 12.sp)
    }
}

private fun fmtDate(sec: Long): String {
    if (sec <= 0) return ""
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 60 -> "刚刚"; d < 3600 -> "${d / 60} 分钟前"; d < 86400 -> "${d / 3600} 小时前"
        else -> {
            val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = sec * 1000 }.get(Calendar.YEAR)
            SimpleDateFormat(if (sameYear) "M月d日 HH:mm" else "yyyy年M月d日", Locale.CHINA).format(Date(sec * 1000))
        }
    }
}

private val URL_RE = Regex("https?://[^\\s]+")
/** iOS NSDataDetector links: tappable URLs in post text. */
private fun linkify(t: String, accent: Color) = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    for (m in URL_RE.findAll(t)) {
        append(t.substring(i, m.range.first))
        pushLink(androidx.compose.ui.text.LinkAnnotation.Url(m.value, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = accent)))); append(m.value); pop()
        i = m.range.last + 1
    }
    append(t.substring(i))
}

private fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** iOS TGPostCard: avatar · name, text (8 lines + 全文), 朋友圈 media grid, link preview, buttons, footer. */
@Composable
private fun PostCard(p: TGPost, ch: TGChannel?, open: (String) -> Unit, onMedia: (Int) -> Unit) {
    val c = IdjThemeColors
    var expanded by remember { mutableStateOf(false) }
    val name = ch?.displayName ?: "@${p.channel}"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.elevated), contentAlignment = Alignment.Center) {
            if (!ch?.avatar.isNullOrEmpty()) AsyncImage(ch!!.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.take(1), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ch?.isAdult == true) Text("18+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(Color.Red.copy(0.7f)).padding(horizontal = 4.dp))
            }
            if (p.forwardedFrom.isNotEmpty()) Text("转发自 ${p.forwardedFrom}", color = c.secondary, fontSize = 12.sp)
            if (p.text.isNotEmpty()) {
                Text(linkify(p.text, c.accent), color = c.text, fontSize = 17.sp, lineHeight = 22.sp, maxLines = if (expanded) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis)
                if (!expanded && (p.text.length > 260 || p.text.count { it == '\n' } > 7)) Text("全文", color = c.accent, fontSize = 15.sp, modifier = Modifier.clickable { expanded = true })
            }
            if (p.media.isNotEmpty()) MediaGrid(p, onMedia)
            p.preview?.takeIf { it.title.isNotEmpty() || it.desc.isNotEmpty() }?.let { lp ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).clickable { open(lp.url) }.padding(8.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent)); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (lp.site.isNotEmpty()) Text(lp.site, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (lp.title.isNotEmpty()) Text(lp.title, color = c.text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (lp.desc.isNotEmpty()) Text(lp.desc, color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (lp.image.isNotEmpty()) AsyncImage(lp.image, null, Modifier.padding(start = 8.dp).size(54.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                }
            }
            p.buttons.forEach { b ->
                Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(c.text.copy(0.08f)).clickable { open(b.url) }, contentAlignment = Alignment.Center) { Text(b.text, color = c.text, fontSize = 15.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(fmtDate(p.date), color = c.secondary, fontSize = 12.sp)
                if (p.views.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Visibility, null, tint = c.secondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text(p.views, color = c.secondary, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Send, "在 Telegram 中打开", tint = c.secondary, modifier = Modifier.size(18.dp).clickable { open(p.permalink) })
            }
        }
    }
}

/** 1 item full width; 2–4 two columns; 5+ three columns. */
@Composable
private fun MediaGrid(p: TGPost, onMedia: (Int) -> Unit) {
    val m = p.media
    if (m.size == 1) { Cell(m[0], Modifier.fillMaxWidth().aspectRatio(if (m[0].kind == TGMedia.Kind.photo) 4f / 3 else 16f / 9)) { onMedia(0) }; return }
    val cols = if (m.size <= 4) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.indices.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { i -> Cell(m[i], Modifier.weight(1f).aspectRatio(1f)) { onMedia(i) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Cell(it: TGMedia, mod: Modifier, onClick: () -> Unit) {
    Box(mod.clip(RoundedCornerShape(8.dp)).background(IdjThemeColors.elevated).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AsyncImage(it.thumb, null, Modifier.fillMaxSize().then(if (it.spoiler) Modifier.blur(18.dp) else Modifier), contentScale = ContentScale.Crop)
        if (it.kind != TGMedia.Kind.photo) {
            Icon(if (it.video == null) Icons.Filled.Send else Icons.Filled.PlayCircle, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(44.dp))
            if (it.duration > 0) Text(clock(it.duration), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(0.55f)).padding(horizontal = 5.dp, vertical = 2.dp))
        }
    }
}
