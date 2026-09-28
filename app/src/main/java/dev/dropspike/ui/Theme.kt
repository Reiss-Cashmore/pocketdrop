package dev.dropspike.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** PocketDrop palette: violet primary (Twitch-adjacent, not Twitch's), teal for "live". */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDB8FF),
    onPrimary = Color(0xFF34106F),
    primaryContainer = Color(0xFF4C2A99),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF6FDCC6),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005045),
    onTertiaryContainer = Color(0xFF8EF8E1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF110E16),
    onBackground = Color(0xFFE8E0EC),
    surface = Color(0xFF110E16),
    onSurface = Color(0xFFE8E0EC),
    surfaceVariant = Color(0xFF4A4552),
    onSurfaceVariant = Color(0xFFCBC3D3),
    outline = Color(0xFF958E9D),
    outlineVariant = Color(0xFF4A4552),
    surfaceContainerLowest = Color(0xFF0B0910),
    surfaceContainerLow = Color(0xFF19161E),
    surfaceContainer = Color(0xFF1E1A23),
    surfaceContainerHigh = Color(0xFF28242E),
    surfaceContainerHighest = Color(0xFF333039),
    inverseSurface = Color(0xFFE8E0EC),
    inverseOnSurface = Color(0xFF2F2B34),
    inversePrimary = Color(0xFF6A43D1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6A43D1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF24005B),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF006B5C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF8EF8E1),
    onTertiaryContainer = Color(0xFF00201B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1D1A22),
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1D1A22),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF4A4552),
    outline = Color(0xFF7B7582),
    outlineVariant = Color(0xFFCBC4CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F1FB),
    surfaceContainer = Color(0xFFF1EBF5),
    surfaceContainerHigh = Color(0xFFEBE5EF),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    inverseSurface = Color(0xFF322F37),
    inverseOnSurface = Color(0xFFF5EFF7),
    inversePrimary = Color(0xFFCDB8FF),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Fixed status colours (uptime chart, status pills). Mode-independent on purpose. */
object StatusColors {
    val good = Color(0xFF0CA30C)
    val warning = Color(0xFFFAB219)
    val critical = Color(0xFFD03B3B)
}

@Composable
fun DropSpikeTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
