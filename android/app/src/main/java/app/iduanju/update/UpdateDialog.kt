package app.iduanju.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.update.AppRelease
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.launch

/** 发现新版本: notes, then 更新 → progress → 安装 (system installer). 稍后 / 忽略此版本 close it. */
@Composable
fun UpdateDialog(u: AppUpdater) {
    val r = u.offer ?: return
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var hint by remember(r.tag) { mutableStateOf<String?>(null) }
    val phase = u.phase
    AlertDialog(
        onDismissRequest = { if (phase !is AppUpdater.Phase.Downloading) u.dismiss() },
        title = { Text("发现新版本 ${r.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前 ${u.currentVersion.joinToString(".")} · 安装包 ${"%.1f".format(r.apkSize / 1048576.0)} MB", color = c.secondary, fontSize = 13.sp)
                Text(AppRelease.plainNotes(r.notes).ifBlank { r.name }, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                when (phase) {
                    is AppUpdater.Phase.Downloading -> {
                        val p = if (phase.total > 0) phase.read.toFloat() / phase.total else 0f
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = c.accent)
                        Text("正在下载 ${(p * 100).toInt()}%", color = c.secondary, fontSize = 12.sp)
                    }
                    is AppUpdater.Phase.Failed -> Text(phase.message, color = c.accentWarm, fontSize = 13.sp)
                    else -> {}
                }
                hint?.let { Text(it, color = c.accent, fontSize = 12.sp, lineHeight = 17.sp) }
            }
        },
        confirmButton = {
            when (phase) {
                is AppUpdater.Phase.Downloading -> TextButton({}, enabled = false) { Text("下载中…") }
                is AppUpdater.Phase.Ready -> TextButton({
                    if (!u.install(phase.file)) hint = "请允许 i短剧「安装未知应用」，返回后再点一次「安装」。"
                }) { Text("安装") }
                is AppUpdater.Phase.Failed -> TextButton({ scope.launch { u.download(r) } }) { Text("重试") }
                AppUpdater.Phase.Idle -> TextButton({ scope.launch { u.download(r) } }) { Text("更新") }
            }
        },
        dismissButton = {
            if (phase !is AppUpdater.Phase.Downloading) Row {
                TextButton({ u.ignore(r) }) { Text("忽略此版本", color = c.secondary) }
                TextButton({ u.dismiss() }) { Text("稍后") }
            }
        },
    )
}
