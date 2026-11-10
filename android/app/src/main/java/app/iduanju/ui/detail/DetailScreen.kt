package app.iduanju.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.ui.components.posterBadge
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(seriesId: String, onBack: () -> Unit, onPlay: (String, Int) -> Unit) {
    val c = IdjThemeColors
    var d by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId) {
        try { d = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.detail(seriesId, web = false) } }
        catch (e: Exception) { err = e.message }
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when {
            d == null && err == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
            err != null -> Text(err!!, Modifier.align(Alignment.Center), color = c.secondary)
            else -> {
                val detail = d!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        AsyncImage(detail.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(0.4f), c.bg))))
                        IconButton(onBack, Modifier.statusBarsPadding().padding(8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            Text(detail.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    detail.source,
                                    posterBadge(detail.totalEps).ifEmpty { null },
                                    if (detail.favoriteCount > 0) "${app.iduanju.ui.home.formatCount(detail.favoriteCount)}收藏" else null,
                                    detail.author.ifEmpty { null },
                                ).joinToString(" · "),
                                color = Color.White.copy(0.75f), fontSize = 12.sp,
                            )
                        }
                    }
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.text)
                                .clickable { onPlay(seriesId, detail.episodes.firstOrNull()?.ep ?: 1) },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = c.bg)
                            Spacer(Modifier.width(4.dp))
                            Text("继续第${detail.episodes.firstOrNull()?.ep ?: 1}集", color = c.bg, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(48.dp).clip(CircleShape).background(c.elevated), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.FavoriteBorder, null, tint = c.text)
                        }
                    }
                    if (detail.tags.isNotEmpty()) {
                        LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(detail.tags) { t ->
                                Text(t, color = c.secondary, fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.elevated).padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    if (detail.actors.isNotEmpty()) {
                        Text("主演：${detail.actors.joinToString(" / ")}", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    }
                    if (detail.desc.isNotEmpty()) {
                        Text(detail.desc, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    Text("选集 · 共 ${detail.episodes.size} 集", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(detail.episodes.take(40)) { ep ->
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(c.elevated)
                                    .clickable { onPlay(seriesId, ep.ep) },
                                contentAlignment = Alignment.Center,
                            ) { Text("${ep.ep}", color = c.text, fontWeight = FontWeight.Medium) }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}
