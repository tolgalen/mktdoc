package com.tolgalen.mktdoc.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.data.ThemeMode
import com.tolgalen.mktdoc.data.ThemePalette

object Palette {
    val ok = Color(0xFF4CAF50)
    val warn = Color(0xFFFFB300)
    val bad = Color(0xFFEF5350)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3FA9F5),
    onPrimary = Color(0xFF0A1620),
    secondary = Color(0xFF26C6A6),
    tertiary = Color(0xFFF5A623),
    background = Color(0xFF14181C),
    onBackground = Color(0xFFE3E7EA),
    surface = Color(0xFF1E2327),
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = Color(0xFF2A3036),
    onSurfaceVariant = Color(0xFF9AA5AE),
    surfaceContainer = Color(0xFF1E2327),
    surfaceContainerLow = Color(0xFF1A1F23),
    surfaceContainerHigh = Color(0xFF252B30),
    outline = Color(0xFF3A434B),
    error = Color(0xFFEF5350),
)

/**
 * Dark blue (navy): navy backgrounds instead of gray. The containers used by cards, chips and
 * tonal buttons are fixed too, otherwise they would take Material's default purple.
 */
private val NavyColors = darkColorScheme(
    primary = Color(0xFF4FB3FF),
    onPrimary = Color(0xFF04162B),
    secondary = Color(0xFF2ED3B7),
    tertiary = Color(0xFFF5A623),
    background = Color(0xFF0A1424),
    onBackground = Color(0xFFE2EAF5),
    surface = Color(0xFF111F36),
    onSurface = Color(0xFFE2EAF5),
    surfaceVariant = Color(0xFF1A2C4A),
    onSurfaceVariant = Color(0xFF93A8C6),
    surfaceContainerLowest = Color(0xFF08111F),
    surfaceContainerLow = Color(0xFF0E1A2E),
    surfaceContainer = Color(0xFF111F36),
    surfaceContainerHigh = Color(0xFF16273F),
    surfaceContainerHighest = Color(0xFF1C3050),
    secondaryContainer = Color(0xFF1E3A60),
    onSecondaryContainer = Color(0xFFD0E4FF),
    outline = Color(0xFF2B4166),
    outlineVariant = Color(0xFF1F3252),
    error = Color(0xFFEF5350),
)

/**
 * Black for OLED screens: pure black background (those pixels switch off) and almost black cards,
 * with the containers fixed too so they do not take Material's default purple-gray.
 */
private val BlackColors = darkColorScheme(
    primary = Color(0xFF3FA9F5),
    onPrimary = Color(0xFF00111F),
    secondary = Color(0xFF26C6A6),
    tertiary = Color(0xFFF5A623),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF161616),
    secondaryContainer = Color(0xFF1C2A36),
    onSecondaryContainer = Color(0xFFD0E4FF),
    outline = Color(0xFF2C2C2C),
    outlineVariant = Color(0xFF1F1F1F),
    error = Color(0xFFEF5350),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFE08600),
    background = Color(0xFFF1F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EBEF),
    onSurfaceVariant = Color(0xFF55616B),
    outline = Color(0xFFC3CBD2),
)

// Color schemes generated from a seed color.
private class Seeds(val primary: Color, val primaryDark: Color, val secondary: Color, val tertiary: Color)

private fun seeds(p: ThemePalette): Seeds? = when (p) {
    ThemePalette.OCEAN -> Seeds(Color(0xFF1F5FA8), Color(0xFF9CC8FF), Color(0xFF535F70), Color(0xFF00696B))
    ThemePalette.FOREST -> Seeds(Color(0xFF1E7A52), Color(0xFF7BE0A8), Color(0xFF4E6357), Color(0xFF3B6470))
    ThemePalette.VIOLET -> Seeds(Color(0xFF6A4FB3), Color(0xFFCDBDFF), Color(0xFF625B71), Color(0xFF7D5260))
    ThemePalette.AMBER -> Seeds(Color(0xFF8A5A00), Color(0xFFFFB951), Color(0xFF6E5D40), Color(0xFF4F6541))
    ThemePalette.ROSE -> Seeds(Color(0xFFA23A5A), Color(0xFFFFB1C4), Color(0xFF74565E), Color(0xFF7C5635))
    ThemePalette.SLATE -> Seeds(Color(0xFF3F5566), Color(0xFFB2C8DA), Color(0xFF55606A), Color(0xFF5C5D72))
    ThemePalette.MKTDOC, ThemePalette.MATERIAL -> null
}

/** Sample color of a palette, for the Settings picker. */
fun paletteSwatch(p: ThemePalette): Color = when (p) {
    ThemePalette.MKTDOC -> Color(0xFF3FA9F5)
    ThemePalette.MATERIAL -> Color(0xFF6750A4)
    else -> seeds(p)!!.primary
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
)

private fun generatedLight(s: Seeds): ColorScheme {
    val bg = mix(Color.White, s.primary, 0.03f)
    return lightColorScheme(
        primary = s.primary, onPrimary = Color.White,
        primaryContainer = mix(Color.White, s.primary, 0.18f), onPrimaryContainer = mix(Color.Black, s.primary, 0.55f),
        secondary = s.secondary,
        secondaryContainer = mix(Color.White, s.secondary, 0.16f), onSecondaryContainer = mix(Color.Black, s.secondary, 0.6f),
        tertiary = s.tertiary, tertiaryContainer = mix(Color.White, s.tertiary, 0.16f),
        background = bg, surface = bg,
        surfaceVariant = mix(Color.White, s.secondary, 0.12f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = mix(Color.White, s.primary, 0.04f),
        surfaceContainer = mix(Color.White, s.primary, 0.06f),
        surfaceContainerHigh = mix(Color.White, s.primary, 0.09f),
        surfaceContainerHighest = mix(Color.White, s.primary, 0.12f),
        outlineVariant = mix(Color.White, s.secondary, 0.3f),
    )
}

private fun generatedDark(s: Seeds): ColorScheme {
    val bg = mix(Color(0xFF101413), s.primaryDark, 0.03f)
    return darkColorScheme(
        primary = s.primaryDark, onPrimary = mix(Color.Black, s.primary, 0.5f),
        primaryContainer = mix(Color(0xFF101413), s.primary, 0.55f), onPrimaryContainer = mix(Color.White, s.primaryDark, 0.4f),
        secondary = mix(Color.White, s.secondary, 0.35f),
        secondaryContainer = mix(Color(0xFF101413), s.secondary, 0.45f), onSecondaryContainer = mix(Color.White, s.secondary, 0.2f),
        tertiary = mix(Color.White, s.tertiary, 0.45f), tertiaryContainer = mix(Color(0xFF101413), s.tertiary, 0.45f),
        background = bg, surface = bg,
        surfaceVariant = mix(bg, s.secondary, 0.25f),
        surfaceContainerLowest = Color(0xFF0B0F0E),
        surfaceContainerLow = mix(bg, s.primaryDark, 0.06f),
        surfaceContainer = mix(bg, s.primaryDark, 0.09f),
        surfaceContainerHigh = mix(bg, s.primaryDark, 0.12f),
        surfaceContainerHighest = mix(bg, s.primaryDark, 0.15f),
        outlineVariant = mix(bg, s.secondary, 0.45f),
    )
}

/** Slightly rounded corners: squarer buttons, cards and dialogs. */
val ButtonShape = RoundedCornerShape(4.dp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun MktdocTheme(mode: ThemeMode, palette: ThemePalette, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.NAVY, ThemeMode.BLACK -> true
    }
    val colors = when {
        mode == ThemeMode.NAVY -> NavyColors
        mode == ThemeMode.BLACK -> BlackColors
        // Material You (Android 12+): colors taken from the wallpaper
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        // Base Material Design: the default Material 3 schemes
        palette == ThemePalette.MATERIAL -> if (dark) darkColorScheme() else lightColorScheme()
        seeds(palette) != null -> seeds(palette)!!.let { if (dark) generatedDark(it) else generatedLight(it) }
        dark -> DarkColors
        else -> LightColors
    }
    // enableEdgeToEdge() picks the system bar icon color from the system mode, not from the app
    // theme: with the light theme on a dark system they would be white on white.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
