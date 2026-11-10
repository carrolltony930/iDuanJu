package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            "默认使用本机生成的虚拟设备。若取流被软拒绝（HTTP 200 空包），可在此填入已知可用的 device_id / iid（与公开 iOS 的 stv.hg.purePlayerDevice 相同）。不会写进源码。",
            color = c.secondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("当前 mint：${minted.deviceId} / ${minted.iid}", color = c.secondary, fontSize = 11.sp)
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
        Text("开源许可", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("GPL-3.0 · Media3 / OkHttp / Coil / Kotlin", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
