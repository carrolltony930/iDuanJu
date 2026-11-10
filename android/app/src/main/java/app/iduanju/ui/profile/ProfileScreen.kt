package app.iduanju.ui.profile

import androidx.compose.foundation.background
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
fun ProfileScreen() {
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
        Spacer(Modifier.height(24.dp))
        Text("开源许可", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("GPL-3.0 · Media3 / OkHttp / Coil / Kotlin", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** iOS SourcesView 「苹果 CMS 采集站」: add by API URL (validated by ?ac=list), toggle, remove. */
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
    Text("粘贴 .idjplugin 的 https 链接或文件内容。插件运行在 QuickJS 沙箱里，只能访问 manifest 声明的域名；未成年相关内容一律过滤。",
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
    OutlinedTextField(input, { input = it }, label = { Text("https://….idjplugin 或 {\"manifest\":…}") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
        colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
    Button(enabled = !busy && input.isNotBlank(), onClick = {
        busy = true; msg = null
        scope.launch {
            msg = try {
                val r = withContext(Dispatchers.IO) {
                    val t = input.trim()
                    val text = if (t.startsWith("https://")) app.iduanju.plugin.PluginStore.download(t) else t
                    store.install(text, if (t.startsWith("https://")) t else "paste")
                }
                input = ""; "已安装「${r.name}」${r.version}" + if (r.type == "comic") "（漫画插件：阅读器移植中）" else ""
            } catch (e: app.iduanju.core.plugin.PluginException) { e.friendly } catch (e: Exception) { e.message ?: "安装失败" }
            busy = false
        }
    }) { Text(if (busy) "校验中…" else "安装") }
    msg?.let { Text(it, color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
}
