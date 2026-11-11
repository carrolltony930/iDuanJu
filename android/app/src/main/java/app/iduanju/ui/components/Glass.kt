package app.iduanju.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import app.iduanju.ui.theme.IdjThemeColors

@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    haze: dev.chrisbanes.haze.HazeState? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = IdjThemeColors
    // iOS .ultraThinMaterial + surface 55%: real backdrop blur on API 31+ (Haze), translucent fallback below.
    val blur = haze != null && android.os.Build.VERSION.SDK_INT >= 31
    Row(
        modifier
            .shadow(18.dp, RoundedCornerShape(percent = 50), ambientColor = Color.Black.copy(0.18f), spotColor = Color.Black.copy(0.18f))
            .clip(RoundedCornerShape(percent = 50))
            .then(if (blur) Modifier.hazeEffect(haze!!, dev.chrisbanes.haze.HazeStyle(c.surface, emptyList(), 24.dp, noiseFactor = 0f)) else Modifier)
            .background(c.surface.copy(alpha = if (blur) 0.55f else 0.92f))
            .border(
                0.8.dp,
                Brush.verticalGradient(listOf(c.text.copy(0.18f), c.text.copy(0.05f))),
                RoundedCornerShape(percent = 50),
            )
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassTabBar(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    haze: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassCapsule(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(horizontal = 16.dp), haze = haze) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (on) c.text.copy(0.10f) else Color.Transparent)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        icon(on)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label,
                            color = if (on) c.text else c.secondary,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) c.text else c.elevated)
            .then(if (!selected) Modifier.border(0.6.dp, c.text.copy(0.12f), RoundedCornerShape(percent = 50)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = if (selected) c.bg else c.secondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

fun posterBadge(total: Int, updated: Int = 0): String = when {
    total > 0 && updated > 0 && updated < total -> "更新至${updated}集"
    total > 0 -> "全${total}集"
    updated > 0 -> "更新至${updated}集"
    else -> ""
}

val PosterShape = RoundedCornerShape(10.dp)
val CardShape = RoundedCornerShape(16.dp)
