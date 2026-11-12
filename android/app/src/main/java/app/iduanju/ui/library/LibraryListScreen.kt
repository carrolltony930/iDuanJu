package app.iduanju.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage

/** iOS LibraryListView: 我的收藏 (3-column grid) / 观看历史 (rows with progress; ✕ removes a record). */
@Composable
fun LibraryListScreen(kind: String, onBack: () -> Unit, onOpenDetail: (String) -> Unit) {
    val c = IdjThemeColors
    val lib = IdjApp.instance.library
    val fav = kind == "favorites"
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text) }
            Text(if (fav) "我的收藏" else "观看历史", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            if (!fav && lib.history.isNotEmpty()) TextButton({ lib.clearHistory() }, Modifier.align(Alignment.CenterEnd)) { Text("清空") }
        }
        if (fav) {
            val items = lib.favorites.entries.sortedByDescending { it.value.third }
            if (items.isEmpty()) Empty("还没有收藏")
            else LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(items, key = { it.key }) { (sid, v) ->
                    Column(Modifier.clickable { onOpenDetail(sid) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(c.elevated)) {
                            AsyncImage(v.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text(v.first, color = c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            val items = lib.continueWatching
            if (items.isEmpty()) Empty("还没有观看记录")
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.seriesId }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenDetail(p.seriesId) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(64.dp, 86.dp).clip(RoundedCornerShape(8.dp)).background(c.elevated)) {
                            AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val s = p.seconds.toInt()
                            Text("${p.resumeLabel} · ${s / 60}:${"%02d".format(s % 60)}", color = c.secondary, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { p.fraction }, Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.text.copy(0.12f), gapSize = 0.dp, drawStopIndicator = {})
                        }
                        Text("✕", color = c.secondary, fontSize = 16.sp, modifier = Modifier.clickable { lib.removeProgress(p.seriesId) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(t: String) {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Text(t, color = IdjThemeColors.secondary, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}
