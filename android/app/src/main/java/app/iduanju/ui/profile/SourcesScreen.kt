package app.iduanju.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.theme.IdjThemeColors

/**
 * iOS SourcesView (我的 → 片源管理): 内置 sources, adult gate (with 18+ confirmation), 插件与采集站 link,
 * playback options. The 红果 device override is an advanced option, folded at the bottom.
 */
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val go = LocalNavigate.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmAdult by remember { mutableStateOf(false) }
    var adStrip by remember { mutableStateOf(app.iduanju.core.hls.HLSAdFilter.enabled) }
    var message by remember { mutableStateOf<String?>(null) }
    val adult = ia.cms.adultUnlocked

    SettingsPage("片源管理", onBack) {
        Group("内置") {
            BuiltinRow("红果短剧", ProfileIcons.play)
        }
        Group("成人内容（默认隐藏）", footer = "开启后黄果、标记为成人的采集站 / 插件 / Telegram 频道 / 漫画插件才会出现在首页、搜索、频道与漫画；所有结果一律过滤未成年人与非自愿题材。") {
            SwitchRow("显示成人片源", ProfileIcons.film, "黄果短剧、成人采集站 / 插件 / 频道 / 漫画 · 仅限年满 18 周岁", adult) {
                if (it) confirmAdult = true else { ia.cms.adultUnlocked = false; message = "已隐藏成人片源" }
            }
        }
        Group("插件与采集站") {
            LinkRow("插件管理", ProfileIcons.plugin, sub = "JS 插件、订阅、苹果 CMS 采集站；成人插件在这里解锁后才显示") { go("plugins") }
        }
        Group("播放") {
            SwitchRow("去广告", ProfileIcons.adblock, "去掉 HLS 片源里插播的广告片段（adjump、异域名短片段、重复片段）", adStrip) {
                adStrip = it; app.iduanju.core.hls.HLSAdFilter.enabled = it
                ctx.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE).edit().putBoolean("stv.hls.adstrip", it).apply()
            }
        }
        DeviceOverrideGroup()
        Note(message)
    }
    if (confirmAdult) AlertDialog(
        onDismissRequest = { confirmAdult = false },
        title = { Text("显示成人片源？") },
        text = { Text("仅限年满 18 周岁的用户。开启后黄果与标记为成人的片源会出现在首页、搜索、频道与漫画；可随时在这里关闭。") },
        confirmButton = { TextButton({ ia.cms.adultUnlocked = true; confirmAdult = false; message = "已显示成人片源" }) { Text("我已年满 18 周岁") } },
        dismissButton = { TextButton({ confirmAdult = false }) { Text("取消") } },
    )
}

@Composable
private fun BuiltinRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String? = null) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 16.sp)
            sub?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
        }
        Tag("内置")
    }
}

/** 高级：红果播放设备覆盖 (iOS `stv.hg.purePlayerDevice`). Collapsed by default; empty = register automatically. */
@Composable
private fun DeviceOverrideGroup() {
    val c = IdjThemeColors
    val settings = IdjApp.instance.settings
    var open by remember { mutableStateOf(settings.playerDeviceOverride() != null) }
    var did by remember { mutableStateOf(settings.playerDeviceOverride()?.first ?: "") }
    var iid by remember { mutableStateOf(settings.playerDeviceOverride()?.second ?: "") }
    var saved by remember { mutableStateOf<String?>(null) }
    Group("高级", footer = if (open) "留空时自动注册一台播放设备（只保存在内存里）；填写后所有取流都使用这台设备，不会写进源码。" else null) {
        LinkRow("红果播放设备", ProfileIcons.device, trailing = if (settings.playerDeviceOverride() != null) "自定义" else "自动") { open = !open }
        if (open) Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = TextFieldDefaults.colors(focusedContainerColor = c.bg, unfocusedContainerColor = c.bg)
            OutlinedTextField(did, { did = it }, label = { Text("device_id") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            OutlinedTextField(iid, { iid = it }, label = { Text("iid") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("保存", enabled = did.isNotBlank() && iid.isNotBlank()) { settings.setPlayerDeviceOverride(did.trim(), iid.trim()); saved = "已保存，之后取流使用这台设备" }
                TextAction("恢复自动") { did = ""; iid = ""; settings.setPlayerDeviceOverride(null, null); saved = "已恢复自动注册" }
            }
            saved?.let { Text(it, color = c.accent, fontSize = 12.sp) }
        }
    }
}
