package app.iduanju.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding(), contentAlignment = Alignment.Center) {
        Text("搜索（Medusa 签名下一里程碑）", color = c.secondary, fontSize = 14.sp)
    }
}
