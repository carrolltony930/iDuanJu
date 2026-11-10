package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.ui.theme.IdjThemeColors

@Composable
fun ProfileScreen(onOpenDetail: (String) -> Unit = {}) {
    val c = IdjThemeColors
    val settings = IdjApp.instance.settings
    var did by remember { mutableStateOf(settings.playerDeviceOverride()?.first ?: "") }
    var iid by remember { mutableStateOf(settings.playerDeviceOverride()?.second ?: "") }
    val minted = remember { HGPlayerDevice.current() }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(20.dp).padding(bottom = 100.dp),
    ) {
        Text("我的", color = c.text, fontWeight = FontWeight.Bold, fontSize = 28.sp)
        Spacer(Modifier.height(20.dp))
        ProfileTop(onOpenDetail)
        Spacer(Modifier.height(24.dp))
        Text("红果播放设备", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "默认每次取流现注册一台一次性设备（实测：同档注册设备放行一次，本机虚拟设备会被软拒绝）。如有已知可用的 device_id / iid 可在此覆盖（与公开 iOS 的 stv.hg.purePlayerDevice 相同），不会写进源码。",
            color = c.secondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("取流方式：" + (if (IdjApp.instance.settings.playerDeviceOverride() != null) "覆盖设备" else "一次性注册设备"), color = c.secondary, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(did, { did = it }, label = { Text("device_id") }, modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(iid, { iid = it }, label = { Text("iid") }, modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { settings.setPlayerDeviceOverride(did, iid) }) { Text("保存覆盖") }
            Button(onClick = { did = ""; iid = ""; settings.setPlayerDeviceOverride(null, null) }) { Text("清除") }
        }
        Spacer(Modifier.height(24.dp))
        CMSSection()
        Spacer(Modifier.height(20.dp))
        PluginSection()
        SubscriptionSection()
        BundledSection()
        Spacer(Modifier.height(24.dp))
        Text("开源许可", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("GPL-3.0 · Media3 / OkHttp / Coil / Kotlin", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** iOS SourcesView 「苹果 CMS 采集站」: add by API URL (validated by ?ac=list), toggle, remove. */
/** iOS ProfileView top: 继续观看, 我的收藏 grid, 片源管理 (adult gate with an age confirmation, 去广告), maintenance rows, footer. */
@Composable
private fun ProfileTop(onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val lib = ia.library
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmAdult by remember { mutableStateOf(false) }
    var adStrip by remember { mutableStateOf(app.iduanju.core.hls.HLSAdFilter.enabled) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (lib.history.isNotEmpty()) Box(Modifier.offset(x = (-16).dp).fillMaxWidth()) { app.iduanju.ui.home.ContinueRow(lib.history.toList(), onOpenDetail) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的收藏", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("全部 ${lib.favorites.size}", color = c.secondary, fontSize = 15.sp)
            }
            val favs = lib.favorites.entries.sortedByDescending { it.value.third }.take(6)
            if (favs.isEmpty()) Text("在播放页右侧点 ♥ 收藏", color = c.secondary, fontSize = 14.sp)
            else favs.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (sid, v) ->
                        Column(Modifier.weight(1f).clickable { onOpenDetail(sid) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(c.elevated)) {
                                coil.compose.AsyncImage(v.second, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            }
                            Text(v.first, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Card {
            Text("片源管理", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
            ToggleRow("成人片源（默认隐藏）", "黄果、标记为成人的采集站 / 插件 / Telegram 频道 / 漫画插件；所有结果一律过滤未成年人与非自愿题材", ia.cms.adultUnlocked) {
                if (it) confirmAdult = true else { ia.cms.adultUnlocked = false; message = "已隐藏成人片源" }
            }
            ToggleRow("去广告", "去掉 HLS 片源里插播的广告片段（adjump、异域名短片段、重复片段）", adStrip) {
                adStrip = it; app.iduanju.core.hls.HLSAdFilter.enabled = it
                ctx.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE).edit().putBoolean("stv.hls.adstrip", it).apply()
            }
        }
        Card {
            ActionRow("重置红果设备注册") { withContext(Dispatchers.IO) { ia.hongguo.resetDevice() }; "已重置红果设备" }.invoke(scope) { message = it }
            ActionRow("重置黄果访客会话") { withContext(Dispatchers.IO) { ia.huangguo.resetSession() }; "已清除黄果会话" }.invoke(scope) { message = it }
            ActionRow("清空封面缓存") { coil.Coil.imageLoader(ctx).let { l -> l.memoryCache?.clear(); withContext(Dispatchers.IO) { l.diskCache?.clear() } }; "封面缓存已清空" }.invoke(scope) { message = it }
            ActionRow("清空播放缓存") { withContext(Dispatchers.IO) { java.io.File(ctx.cacheDir, "hg-play").deleteRecursively() }; "播放缓存已清空" }.invoke(scope) { message = it }
        }
        val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
        Text(message ?: "所有数据由本机直连上游，观看记录和收藏只保存在本机。\ni短剧 $ver", color = if (message != null) c.accent else c.secondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
    if (confirmAdult) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmAdult = false },
        title = { Text("显示成人片源？") },
        text = { Text("仅限年满 18 周岁的用户。开启后黄果与标记为成人的片源会出现在首页、搜索、频道与漫画；可随时在这里关闭。") },
        confirmButton = { TextButton({ ia.cms.adultUnlocked = true; confirmAdult = false; message = "已显示成人片源" }) { Text("我已年满 18 周岁") } },
        dismissButton = { TextButton({ confirmAdult = false }) { Text("取消") } },
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(IdjThemeColors.surface), content = content)
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp)
            Text(sub, color = c.secondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(on, onChange)
    }
}

private class ActionRow(val title: String, val work: suspend () -> String) {
    @Composable
    fun invoke(scope: kotlinx.coroutines.CoroutineScope, onDone: (String) -> Unit) {
        val c = IdjThemeColors
        Row(Modifier.fillMaxWidth().clickable { scope.launch { onDone(runCatching { work() }.getOrElse { it.message ?: "失败" }) } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("›", color = c.secondary, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CMSSection() {
    val c = IdjThemeColors
    val store = IdjApp.instance.cms
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("苹果 CMS 采集站", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text("填 maccms v10 采集接口（…/api.php/provide/vod/），仅 https；只播放直链 m3u8 / mp4 线路，未成年相关内容一律过滤。",
        color = c.secondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    store.sites.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name + if (s.adult) " · 成人" else "", color = c.text, fontSize = 14.sp)
                Text("${runCatching { java.net.URI(s.api).host }.getOrNull() ?: ""} · ${s.categories.size} 个分类", color = c.secondary, fontSize = 11.sp)
            }
            Switch(s.enabled, { store.setEnabled(s.key, it) })
            TextButton(onClick = { store.remove(s.key) }) { Text("删除") }
        }
    }
    OutlinedTextField(name, { name = it }, label = { Text("名称（可空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(api, { api = it }, label = { Text("采集接口 https://…") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(adult, { adult = it }); Text("成人站（需在设置里解锁才显示）", color = c.secondary, fontSize = 12.sp)
    }
    Button(enabled = !busy && api.isNotBlank(), onClick = {
        busy = true; msg = null
        scope.launch {
            msg = try { val s = withContext(Dispatchers.IO) { store.add(name, api, adult) }; api = ""; name = ""; "已添加「${s.name}」· ${s.categories.size} 个分类" }
                  catch (e: Exception) { e.message ?: "添加失败" }
            busy = false
        }
    }) { Text(if (busy) "校验中…" else "添加") }
    msg?.let { Text(it, color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
}

/** iOS 推荐插件（App 内置）: offered, never auto-installed; adult ones only behind the gate. */
@Composable
private fun BundledSection() {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val store = ia.plugins
    val all = remember { store.bundledManifests() }
    if (all.isEmpty()) return
    val list = all.filter { !it.second.adult || ia.cms.adultUnlocked }
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    if (list.isEmpty()) {
        Text("App 内置了 ${all.size} 个漫画插件（成人内容），打开成人开关后才会显示在这里。", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    Text("推荐插件（App 内置）", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
    list.forEach { (file, m) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name + if (m.adult) " · 18+" else "", color = c.text, fontSize = 14.sp)
                Text(m.description ?: "", color = c.secondary, fontSize = 11.sp, maxLines = 2)
            }
            val inst = store.plugins.firstOrNull { it.id == m.id }
            val newer = inst != null && app.iduanju.core.plugin.PluginManifest.compare(m.version, inst.version) > 0
            if (inst != null && !newer) Text("已安装", color = c.secondary, fontSize = 12.sp)
            else TextButton(onClick = { scope.launch {
                msg = try { val r = withContext(Dispatchers.IO) { store.install(store.bundledText(file), "内置") }; "已安装「${r.name}」${r.version}" }
                      catch (x: app.iduanju.core.plugin.PluginException) { x.friendly } catch (x: Exception) { x.message ?: "安装失败" }
            } }) { Text(if (newer) "更新" else "安装") }
        }
    }
    msg?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
}

/** iOS PluginViews.subscriptionBlock: list entries, install on demand (never auto-install), add 采集站. */
@Composable
private fun SubscriptionSection() {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val store = ia.plugins
    if (store.subscriptions.isEmpty()) return
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("订阅", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(enabled = !busy, onClick = { busy = true; scope.launch { withContext(Dispatchers.IO) { store.refreshSubscriptions() }; busy = false } }) { Text(if (busy) "刷新中…" else "刷新") }
    }
    val unlocked = ia.cms.adultUnlocked
    store.subscriptions.forEach { s ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifEmpty { s.url }, color = c.text, fontSize = 14.sp)
                    Text(s.error ?: s.url, color = if (s.error == null) c.secondary else c.accentWarm, fontSize = 11.sp, maxLines = 1)
                }
                TextButton(onClick = { store.removeSubscription(s.url) }) { Text("删除") }
            }
            s.plugins.filter { it.adult != true || unlocked }.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name + if (e.adult != false) " · 18+" else "", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val inst = store.plugins.firstOrNull { it.id == e.id }
                    val newer = inst != null && e.version != null && app.iduanju.core.plugin.PluginManifest.compare(e.version!!, inst.version) > 0
                    if (inst != null && !newer) Text("已安装", color = c.secondary, fontSize = 12.sp)
                    else TextButton(onClick = { scope.launch {
                        msg = try { val r = withContext(Dispatchers.IO) { store.interpret(e.manifest, "订阅：" + s.url) }
                            (r as? app.iduanju.plugin.PluginStore.Pasted.Plugin)?.let { "已安装「${it.rec.name}」${it.rec.version}" } ?: "已更新订阅"
                        } catch (x: app.iduanju.core.plugin.PluginException) { x.friendly } catch (x: Exception) { x.message ?: "安装失败" }
                    } }) { Text(if (newer) "更新 ${e.version}" else "安装") }
                }
            }
            s.cms.filter { it.adult != true || unlocked }.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采集站 · " + e.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val added = ia.cms.sites.any { st -> runCatching { app.iduanju.core.cms.MacCMS.normalize(e.api) == st.api }.getOrDefault(false) }
                    if (added) Text("已添加", color = c.secondary, fontSize = 12.sp)
                    else TextButton(onClick = { scope.launch {
                        msg = try { val st = withContext(Dispatchers.IO) { ia.cms.add(e.name, e.api, e.adult ?: false, "订阅：" + s.url) }; "已添加采集站 ${st.name}" }
                              catch (x: Exception) { x.message ?: "添加失败" }
                    } }) { Text("添加") }
                }
            }
        }
    }
    msg?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
}

/** iOS 插件 (idj1): install from an https .idjplugin URL or a pasted bundle; QuickJS sandbox, domain allow-list. */
@Composable
private fun PluginSection() {
    val c = IdjThemeColors
    val store = IdjApp.instance.plugins
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("插件（idj1）", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text("粘贴 .idjplugin / manifest / 订阅列表的 https 链接，或 .idjplugin 文件内容。插件运行在 QuickJS 沙箱里，只能访问 manifest 声明的域名；未成年相关内容一律过滤。",
        color = c.secondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    store.plugins.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name + if (p.adult) " · 成人" else "", color = c.text, fontSize = 14.sp)
                Text("${p.id} · ${p.version} · ${when (p.type) { "video" -> "长视频"; "shortdrama" -> "短剧"; "comic" -> "漫画"; "live" -> "直播"; else -> p.type }}", color = c.secondary, fontSize = 11.sp)
            }
            Switch(p.enabled, { store.setEnabled(p.id, it) })
            TextButton(onClick = { store.remove(p.id) }) { Text("删除") }
        }
    }
    OutlinedTextField(input, { input = it }, label = { Text("插件 / manifest / 订阅 https 链接，或 {\"manifest\":…}") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
        colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
    Button(enabled = !busy && input.isNotBlank(), onClick = {
        busy = true; msg = null
        scope.launch {
            msg = try {
                val r = withContext(Dispatchers.IO) { store.interpret(input) }
                input = ""
                when (r) {
                    is app.iduanju.plugin.PluginStore.Pasted.Plugin -> "已安装「${r.rec.name}」${r.rec.version}"
                    is app.iduanju.plugin.PluginStore.Pasted.Subscription -> "这是订阅列表，已添加到「订阅」（${r.sub.plugins.size} 个插件 / ${r.sub.cms.size} 个采集站）"
                }
            } catch (e: app.iduanju.core.plugin.PluginException) { e.friendly } catch (e: Exception) { e.message ?: "安装失败" }
            busy = false
        }
    }) { Text(if (busy) "校验中…" else "安装") }
    msg?.let { Text(it, color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
}
