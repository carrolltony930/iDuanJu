package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors

/** Shared building blocks for 我的 / 片源管理 / 插件 — iOS grouped rows (ProfileView.link, SourcesView.group). */

/** Sub-page scaffold: back arrow + centred title (iOS inline navigation title), scrolling column below. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text) }
            Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 40.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** iOS SourcesView.group: small grey caption, then a rounded surface card of rows with inset dividers. */
@Composable
internal fun Group(title: String? = null, footer: String? = null, rows: @Composable ColumnScope.() -> Unit) {
    val c = IdjThemeColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface), content = rows)
        footer?.let { Text(it, color = c.secondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = IdjThemeColors.text.copy(0.08f))

/** iOS ProfileView.link: icon · title · trailing secondary text · chevron. */
@Composable
internal fun LinkRow(title: String, icon: ImageVector?, trailing: String? = null, sub: String? = null, tint: Color? = null, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = tint ?: c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = tint ?: c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        trailing?.let { Text(it, color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp)) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.secondary, modifier = Modifier.size(20.dp))
    }
}

/** iOS Toggle row with icon, title and caption. */
@Composable
internal fun SwitchRow(title: String, icon: ImageVector?, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 16.sp)
            sub?.let { Text(it, color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        Spacer(Modifier.width(10.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

/** Small grey capsule tag ("内置", "漫画", "18+"). */
@Composable
internal fun Tag(text: String) {
    val c = IdjThemeColors
    Text(text, color = c.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.text.copy(0.07f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
internal fun Note(text: String?, error: Boolean = false) {
    text ?: return
    val c = IdjThemeColors
    Text(text, color = if (error) c.accentWarm else c.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
internal fun TextAction(text: String, enabled: Boolean = true, onClick: () -> Unit) =
TextButton(onClick, enabled = enabled) { Text(text, color = if (enabled) IdjThemeColors.accent else IdjThemeColors.secondary) }

internal object ProfileIcons {
    val history = Icons.Outlined.History
    val star = Icons.Outlined.StarOutline
    val heart = Icons.Outlined.FavoriteBorder
    val telegram = Icons.Outlined.Send
    val manga = Icons.AutoMirrored.Outlined.MenuBook
    val download = Icons.Outlined.Download
    val sources = Icons.Outlined.Layers
    val plugin = Icons.Outlined.Extension
    val license = Icons.Outlined.Description
    val reset = Icons.Outlined.Refresh
    val key = Icons.Outlined.Key
    val photos = Icons.Outlined.PhotoLibrary
    val trash = Icons.Outlined.Delete
    val play = Icons.Outlined.SmartDisplay
    val film = Icons.Outlined.Movie
    val globe = Icons.Outlined.Language
    val link = Icons.Outlined.Link
    val file = Icons.Outlined.NoteAdd
    val paste = Icons.Outlined.ContentPaste
    val sync = Icons.Outlined.Sync
    val add = Icons.Outlined.AddCircleOutline
    val server = Icons.Outlined.Dns
    val adblock = Icons.Outlined.Block
    val device = Icons.Outlined.PhoneAndroid
}
