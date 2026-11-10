package app.iduanju.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Pixel-matched to iOS STVTheme (Theme.swift). */
data class IdjColors(
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val accentWarm: Color,
)

val LocalIdjColors = staticCompositionLocalOf {
    IdjColors(
        bg = Color(0xFF111317), surface = Color(0xFF1C1F25), elevated = Color(0xFF393D42),
        text = Color(0xFFEEF0F3), secondary = Color(0xFFADB5BF),
        accent = Color(0xFF8AB4FF), accentWarm = Color(0xFFF2B55C),
    )
}

private val Dark = IdjColors(
    bg = Color(0xFF111317), // 0.067,0.075,0.090
    surface = Color(0xFF1C1F25), // 0.110,0.122,0.145
    elevated = Color(0xFF393D42), // 0.224,0.239,0.259
    text = Color(0xFFEEF0F3), // 0.933,0.941,0.953
    secondary = Color(0xFFADB5BF), // 0.68,0.71,0.75
    accent = Color(0xFF8AB4FF), // 0.541,0.706,1
    accentWarm = Color(0xFFF2B55C), // 0.949,0.710,0.361
)

private val Light = IdjColors(
    bg = Color(0xFFF4F5F7), // 0.957,0.961,0.969
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFE8EAEE), // 0.910,0.918,0.933
    text = Color(0xFF121418), // 0.071,0.078,0.094
    secondary = Color(0xFF5F6672), // 0.373,0.400,0.447
    accent = Color(0xFF286DEF), // 0.157,0.427,0.937
    accentWarm = Color(0xD58514), // 0.835,0.522,0.078 — fix
)

@Composable
fun IdjTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light.copy(accentWarm = Color(0xFFD58514))
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.bg, background = colors.bg,
        surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
        secondary = colors.secondary,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = Color.White, background = colors.bg,
        surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
        secondary = colors.secondary,
    )
    CompositionLocalProvider(LocalIdjColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val IdjThemeColors: IdjColors
    @Composable get() = LocalIdjColors.current
